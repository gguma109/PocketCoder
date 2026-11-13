#!/bin/sh
set -eu
cd "$(dirname "$0")"
gradle --no-daemon serverJar selfTest
