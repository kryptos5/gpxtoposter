#!/usr/bin/env python3
"""
HTTP API for the GPX map poster generator.

Wraps create_gpx_map_poster so a phone app (or any HTTP client) can list Strava
activities, request posters and download the rendered files. Rendering takes
tens of seconds, so poster requests return a job immediately; poll the job and
download the file once its status is "done".

Run:
  uvicorn server:app --host 0.0.0.0 --port 8000

Environment:
  POSTER_API_KEY        If set, every request (except /health) must send it in
                        the X-API-Key header. Strongly recommended.
  STRAVA_CLIENT_ID      Strava app credentials (see strava_source.py); also run
  STRAVA_CLIENT_SECRET  `python strava_source.py auth` once before using Strava.
"""

import os
import re
import secrets
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Literal

import matplotlib

matplotlib.use("Agg")  # Render off-screen; no GUI in a server process

# create_map_poster resolves themes/, fonts/ and posters/ relative to the CWD.
os.chdir(Path(__file__).resolve().parent)

# pylint: disable=wrong-import-position
from fastapi import (APIRouter, Depends, FastAPI, File, Form, HTTPException,  # noqa: E402
                     Security, UploadFile)
from fastapi.responses import FileResponse  # noqa: E402
from fastapi.security import APIKeyHeader  # noqa: E402
from pydantic import BaseModel, Field, ValidationError  # noqa: E402

import create_gpx_map_poster as gpx_poster  # noqa: E402
import create_map_poster as cmp  # noqa: E402
import strava_source  # noqa: E402
import track_files  # noqa: E402

MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MEDIA_TYPES = {
    "png": "image/png",
    "svg": "image/svg+xml",
    "pdf": "application/pdf"
}


class PosterOptions(BaseModel):
    theme: str = "terracotta"
    city: str | None = None
    country: str | None = None
    distance: int | None = Field(None, gt=0, description="Map radius in meters")
    width: float = Field(12, gt=0, le=20, description="Inches")
    height: float = Field(16, gt=0, le=20, description="Inches")
    route_linewidth: float = Field(3.4, gt=0)
    route_padding_ratio: float = Field(0.05, ge=0, le=1)
    font_family: str | None = None
    format: Literal["png", "svg", "pdf"] = "png"
    show_coordinates: bool = True


class StravaPosterRequest(PosterOptions):
    activity_id: int


class Job(BaseModel):
    id: str
    status: Literal["queued", "running", "done", "failed"]
    source: str
    format: str
    created_at: datetime
    finished_at: datetime | None = None
    error: str | None = None
    file_name: str | None = None
    output_file: str | None = Field(None, exclude=True)


class StravaActivity(BaseModel):
    id: int
    name: str
    sport_type: str | None = None
    start_date_local: str | None = None
    distance_m: float | None = None
    moving_time_s: int | None = None
    has_gps: bool
    summary_polyline: str | None = None


# create_map_poster keeps the active theme in a module global, so posters must
# render one at a time.
_executor = ThreadPoolExecutor(max_workers=1)
_jobs: dict[str, Job] = {}
_jobs_lock = threading.Lock()

_api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)


def require_api_key(key: str | None = Security(_api_key_header)) -> None:
    expected = os.environ.get("POSTER_API_KEY")
    if expected and not secrets.compare_digest(key or "", expected):
        raise HTTPException(status_code=401,
                            detail="Missing or invalid X-API-Key")


def _validate_theme(theme: str) -> None:
    available = cmp.get_available_themes()
    if theme not in available:
        raise HTTPException(
            status_code=422,
            detail=
            f"Theme '{theme}' not found. Available themes: {', '.join(available)}",
        )


def _run_job(
    job: Job,
    load_points: Callable[[], tuple[list[tuple[float, float]], str]],
    options: PosterOptions,
) -> None:
    job.status = "running"
    try:
        points, output_stem = load_points()
        job.output_file = gpx_poster.generate_poster(
            gpx_points=points,
            output_stem=output_stem,
            theme=options.theme,
            city=options.city,
            country=options.country,
            distance=options.distance,
            width=options.width,
            height=options.height,
            route_linewidth=options.route_linewidth,
            route_padding_ratio=options.route_padding_ratio,
            font_family=options.font_family,
            output_format=options.format,
            show_coordinates=options.show_coordinates,
        )
        job.file_name = Path(job.output_file).name
        job.status = "done"
    except Exception as exc:  # pylint: disable=broad-except
        job.error = str(exc)
        job.status = "failed"
    finally:
        job.finished_at = datetime.now(timezone.utc)


def _submit(
    source: str,
    load_points: Callable[[], tuple[list[tuple[float, float]], str]],
    options: PosterOptions,
) -> Job:
    job = Job(
        id=uuid.uuid4().hex,
        status="queued",
        source=source,
        format=options.format,
        created_at=datetime.now(timezone.utc),
    )
    with _jobs_lock:
        _jobs[job.id] = job
    _executor.submit(_run_job, job, load_points, options)
    return job


def _get_job(job_id: str) -> Job:
    with _jobs_lock:
        job = _jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")
    return job


app = FastAPI(title="GPX Map Poster API")
api = APIRouter(dependencies=[Depends(require_api_key)])


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@api.get("/themes")
def list_themes() -> list[str]:
    return cmp.get_available_themes()


@api.get("/strava/activities", response_model=list[StravaActivity])
def list_strava_activities(page: int = 1,
                           per_page: int = 30) -> list[StravaActivity]:
    if page < 1 or not 1 <= per_page <= 200:
        raise HTTPException(status_code=422,
                            detail="page must be >= 1, per_page 1-200")
    try:
        activities = strava_source.list_activities(page, per_page)
    except RuntimeError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    result = []
    for a in activities:
        polyline = (a.get("map") or {}).get("summary_polyline") or None
        result.append(
            StravaActivity(
                id=a["id"],
                name=a.get("name", ""),
                sport_type=a.get("sport_type"),
                start_date_local=a.get("start_date_local"),
                distance_m=a.get("distance"),
                moving_time_s=a.get("moving_time"),
                has_gps=polyline is not None,
                summary_polyline=polyline,
            ))
    return result


@api.post("/posters/strava", response_model=Job, status_code=202)
def create_strava_poster(request: StravaPosterRequest) -> Job:
    _validate_theme(request.theme)

    def load_points() -> tuple[list[tuple[float, float]], str]:
        points, activity = strava_source.fetch_activity(request.activity_id)
        return points, gpx_poster.strava_output_stem(request.activity_id,
                                                     activity)

    return _submit(f"strava:{request.activity_id}", load_points, request)


@api.post("/posters/gpx", response_model=Job, status_code=202)
async def create_gpx_poster(
        file: UploadFile = File(
            ..., description="GPX, TCX or FIT track file, optionally .gz"),
        options: str = Form(
            "{}", description="PosterOptions as a JSON string"),
) -> Job:
    try:
        poster_options = PosterOptions.model_validate_json(options)
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=exc.errors()) from exc
    _validate_theme(poster_options.theme)

    data = await file.read(MAX_UPLOAD_BYTES + 1)
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Track file too large")
    try:
        points = track_files.parse_track_bytes(data, file.filename or "")
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    stem = re.sub(r"\W+", "_", track_files.track_stem(file.filename
                                                      or "")).strip("_")
    stem = stem or "upload"
    return _submit(f"gpx:{file.filename}", lambda: (points, stem),
                   poster_options)


@api.get("/posters", response_model=list[Job])
def list_jobs() -> list[Job]:
    with _jobs_lock:
        jobs = list(_jobs.values())
    return sorted(jobs, key=lambda j: j.created_at, reverse=True)


@api.get("/posters/{job_id}", response_model=Job)
def get_job(job_id: str) -> Job:
    return _get_job(job_id)


@api.get("/posters/{job_id}/file")
def download_poster(job_id: str) -> FileResponse:
    job = _get_job(job_id)
    if job.status != "done" or not job.output_file:
        raise HTTPException(status_code=409,
                            detail=f"Poster is not ready (status: {job.status})")
    if not Path(job.output_file).exists():
        raise HTTPException(status_code=410,
                            detail="Poster file no longer exists")
    return FileResponse(job.output_file,
                        media_type=MEDIA_TYPES[job.format],
                        filename=job.file_name)


app.include_router(api)

if not os.environ.get("POSTER_API_KEY"):
    print("⚠ POSTER_API_KEY is not set: the API is open to anyone who can "
          "reach this server")
