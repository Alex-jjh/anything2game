#!/usr/bin/env python3
"""Compatibility entry point for the current Playground/Spaces/Me device smoke.

Uses the shared adb driver and acceptance scenario; see
concept_games_spaces_smoke.py --help for its options. The old Learn/Practice
navigation is now exercised through Space-context journey tests.
"""
from concept_games_spaces_smoke import main

if __name__ == "__main__":
    main()
