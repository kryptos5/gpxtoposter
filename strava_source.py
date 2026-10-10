#!/usr/bin/env python3
"""
Strava activity source for the GPX map poster generator.

Fetches the GPS track of a Strava activity as (lat, lon) tuples, the same shape
produced by create_gpx_map_poster.parse_gpx_points().

Setup (one time):
  1. Create an API application at https://www.strava.com/settings/api
     and set "Authorization Callback Domain" to: localhost
  2. Export your app credentials:
       STRAVA_CLIENT_ID=<id>  STRAVA_CLIENT_SECRET=<secret>
  3. Run:  python strava_source.py auth
     Open the printed URL, approve access, then paste the URL you are
     redirected to (the page itself will fail to load; that's expected).

Tokens are cached in .strava_token.json and refreshed automatically.
"""

import json
import os
import sys
import time
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlencode, urlparse

import requests

API_BASE = "https://www.strava.com/api/v3"
AUTHORIZE_URL = "https://www.strava.com/oauth/authorize"
TOKEN_URL = "https://www.strava.com/oauth/token"
TOKEN_FILE = Path(__file__).resolve().parent / ".strava_token.json"
REQUEST_TIMEOUT = 30


def _client_credentials() -> tuple[str, str]:
    client_id = os.environ.get("STRAVA_CLIENT_ID")
    client_secret = os.environ.get("STRAVA_CLIENT_SECRET")
    if not client_id or not client_secret:
        raise RuntimeError(
            "STRAVA_CLIENT_ID and STRAVA_CLIENT_SECRET environment variables must be set "
            "(see https://www.strava.com/settings/api)")
    return client_id, client_secret


def _save_token(token: dict) -> None:
    TOKEN_FILE.write_text(json.dumps(
        {
            "access_token": token["access_token"],
            "refresh_token": token["refresh_token"],
            "expires_at": token["expires_at"],
        },
        indent=2,
    ),
                          encoding="utf-8")


def _request_token(payload: dict) -> dict:
    client_id, client_secret = _client_credentials()
    resp = requests.post(
        TOKEN_URL,
        data={
            "client_id": client_id,
            "client_secret": client_secret,
            **payload
        },
        timeout=REQUEST_TIMEOUT,
    )
    if resp.status_code != 200:
        raise RuntimeError(
            f"Strava token request failed ({resp.status_code}): {resp.text}")
    token = resp.json()
    _save_token(token)
    return token


def authorize_interactive() -> None:
    """One-time OAuth flow: print the authorize URL and exchange the returned code."""
    client_id, _ = _client_credentials()
    params = {
        "client_id": client_id,
        "redirect_uri": "http://localhost",
        "response_type": "code",
        "approval_prompt": "auto",
        "scope": "activity:read_all",
    }
    print("Open this URL in your browser and approve access:\n")
    print(f"  {AUTHORIZE_URL}?{urlencode(params)}\n")
    redirected = input(
        "Paste the full URL you were redirected to (or just the code): "
    ).strip()

    code = redirected
    if redirected.startswith("http"):
        query = parse_qs(urlparse(redirected).query)
        if "error" in query:
            raise RuntimeError(f"Authorization denied: {query['error'][0]}")
        code = query.get("code", [""])[0]
        if "activity:read" not in query.get("scope", [""])[0]:
            raise RuntimeError(
                "Activity read permission was not granted; re-run and keep "
                "'View data about your activities' checked")
    if not code:
        raise RuntimeError("No authorization code found")

    _request_token({"code": code, "grant_type": "authorization_code"})
    print(f"✓ Strava authorized. Token saved to {TOKEN_FILE.name}")


def get_access_token() -> str:
    """Return a valid access token, refreshing it if it is about to expire."""
    if not TOKEN_FILE.exists():
        raise RuntimeError(
            "Not authorized with Strava yet. Run: python strava_source.py auth")
    token = json.loads(TOKEN_FILE.read_text(encoding="utf-8"))
    if token["expires_at"] - 60 > time.time():
        return token["access_token"]
    token = _request_token({
        "grant_type": "refresh_token",
        "refresh_token": token["refresh_token"],
    })
    return token["access_token"]


def _get(path: str, token: str, params: dict | None = None) -> Any:
    resp = requests.get(
        f"{API_BASE}{path}",
        headers={"Authorization": f"Bearer {token}"},
        params=params,
        timeout=REQUEST_TIMEOUT,
    )
    if resp.status_code == 404:
        raise RuntimeError(
            "Strava activity not found (check the ID and that it belongs to you)"
        )
    if resp.status_code == 429:
        raise RuntimeError(
            "Strava API rate limit reached; try again in 15 minutes")
    if resp.status_code != 200:
        raise RuntimeError(
            f"Strava API request failed ({resp.status_code}): {resp.text}")
    return resp.json()


def list_activities(page: int = 1, per_page: int = 30) -> list[dict]:
    """List the athlete's recent activities (newest first), as returned by Strava."""
    token = get_access_token()
    return _get("/athlete/activities",
                token,
                params={
                    "page": page,
                    "per_page": per_page
                })


def fetch_activity(
        activity_id: int) -> tuple[list[tuple[float, float]], dict]:
    """Fetch an activity's GPS track as (lat, lon) tuples plus its summary metadata."""
    token = get_access_token()
    activity = _get(f"/activities/{activity_id}", token)
    streams = _get(
        f"/activities/{activity_id}/streams",
        token,
        params={
            "keys": "latlng",
            "key_by_type": "true"
        },
    )

    latlng = streams.get("latlng", {}).get("data", [])
    points = [(float(lat), float(lon)) for lat, lon in latlng]
    if len(points) < 2:
        raise ValueError(
            f"Strava activity {activity_id} has no GPS track (indoor or manual activity?)"
        )
    return points, activity


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "auth":
        try:
            authorize_interactive()
        except Exception as exc:
            print(f"✗ Error: {exc}")
            sys.exit(1)
    else:
        print("Usage: python strava_source.py auth")
        sys.exit(1)
