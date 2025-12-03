#! /usr/bin/env sh

set -x

PEER_ID=$1
PEER_HOST=$2
PEER_PUBLIC_PORT=$3
PEER_P2P_PORT=$4

SELF_HOST=$(hostname -I | awk '{print $1}')
SELF_PUBLIC_PORT=$5
SELF_CLI_PORT=$6

CURL_RETRY_CMD="curl --retry 60 --retry-delay 1 --retry-all-errors --fail"

until $(eval "$CURL_RETRY_CMD -f $PEER_HOST:$PEER_PUBLIC_PORT/node/health"); do
  echo "Waiting for health response from PEER $PEER_HOST:$PEER_PUBLIC_PORT"
  sleep 5
done

echo "Got healthy response from $PEER_HOST:$PEER_PUBLIC_PORT"

cat <<EOF > /tmp/peer-to-join.json
{
  "id": "$PEER_ID",
  "ip": "$PEER_HOST",
  "p2pPort": "$PEER_P2P_PORT"
}
EOF

eval "$CURL_RETRY_CMD $SELF_HOST:$SELF_CLI_PORT/cluster/join -H \"Content-Type: application/json\" -d @/tmp/peer-to-join.json"
echo "Join requested"
