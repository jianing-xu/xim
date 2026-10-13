#!/usr/bin/env python3
"""SQLite online backup API: includes committed WAL data without copying live sidecars."""
import argparse
import os
from pathlib import Path
import sqlite3

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('destination', type=Path)
args = parser.parse_args()
source = args.source.resolve(strict=True)
destination = args.destination.resolve()
if destination.exists() or source == destination:
    parser.error('Destination must be a new path; stop the service before restoring to its database path')
destination.parent.mkdir(parents=True, exist_ok=True)
os.umask(0o077)
try:
    with sqlite3.connect(source.as_uri() + '?mode=ro', uri=True, timeout=10) as src:
        with sqlite3.connect(destination) as dst:
            src.backup(dst)
            if dst.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise RuntimeError('Backup integrity check failed')
            if dst.execute('PRAGMA foreign_key_check').fetchall():
                raise RuntimeError('Backup foreign key check failed')
    print(f'Backup verified: {destination}')
except Exception:
    destination.unlink(missing_ok=True)
    raise
