#!/bin/sh

VERSION_TAG="scalagwt"

. $(dirname $0)/env.sh

SCALA_VERSION=2.10.0-scalagwt-SNAPSHOT
SCALA_LIBRARY_VERSION=2.10.0-scalagwt-SNAPSHOT
PROFILE="-P local-scala-trunk,!scala-trunk"

build $*
