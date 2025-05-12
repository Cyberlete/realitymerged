#! /usr/bin/env sh

# Store command line args
new_url=$1
json_location=$2

# Command definition
CURL_RETRY_CMD="curl --retry 60 --retry-delay 1 --retry-all-errors --fail --silent"

# Usage of command line arguments in the command
eval "$CURL_RETRY_CMD $new_url/cluster/join -H \"Content-Type: application/json\" -d @$json_location" && \
  echo "Join requested"