#!/bin/bash

cd `dirname $0`/..
./deploy.sh || exit 1
rsync -crvP a-sync-http-relay-server/target/a-sync-http-relay-server-1.0-SNAPSHOT-executable.jar aleph@kimbox:

