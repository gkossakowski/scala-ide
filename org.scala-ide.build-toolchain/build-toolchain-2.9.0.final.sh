#!/bin/sh

. $(dirname $0)/env.sh

SCALA_VERSION=2.9.0

set_version ${SCALA_VERSION}

build $*
