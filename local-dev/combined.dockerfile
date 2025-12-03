# syntax=docker/dockerfile:1.4
FROM amazoncorretto:11
ARG BUILD_VERSION=1.10.0

RUN yum install -y curl
RUN yum install -y hostname

ENV L0_INITIAL_ID=00b8a56a20fc2e2a0196b8b8f4593ea4f736555506950103eb6fbbe435c0eeb71b32abfe21ae63bb3de8b9afdfa604bfd5837ef61e261611b8a0e5efd92ef1ea
ENV L0_INITIAL_PUBLIC_PORT=9000
ENV L0_INITIAL_P2P_PORT=9001
ENV L0_INITIAL_CLI_PORT=9002

ENV L1_INITIAL_ID=fbf91bc197ece694ae41c84903d2b965b06736cf5cc86dd78cc46107a9d9ac0ca6775d8e663b60aa015dc1ea690ed229390c0b75608521b1285f3e4775ffb8b5
ENV L1_INITIAL_PUBLIC_PORT=9010
ENV L1_INITIAL_P2P_PORT=9011
ENV L1_INITIAL_CLI_PORT=9012

ENV LOCALHOST=0.0.0.0

WORKDIR /app

COPY kubernetes/data/ keys/
COPY target/scala-2.13/cyberApp-assembly*.jar combined.jar
COPY movement.wasm movement.wasm
#reality-combined-assembly-0.0.0+1036-30bff128+20250505-1208.jar
RUN cat <<EOF > start.sh

set -x

export DOCKER_HOST=\$(hostname -i)
# export DOCKER_HOST=172.25.0.2
if [ "\$NODE_COMMAND" = "run-genesis" ]
then
    java -jar combined.jar \\
    --l0--command \$NODE_COMMAND \\
    --l0--env dev \\
    --l0--keyalias alias \\
    --l0--password password \\
    --l0--keystore keys/l0-initial-validator-key.p12 \\
    --l0--l0-ip \$DOCKER_HOST \\
    --l0--ip \$DOCKER_HOST \\
    --l0--public-port \$L0_INITIAL_PUBLIC_PORT \\
    --l0--p2p-port \$L0_INITIAL_P2P_PORT \\
    --l0--cli-port \$L0_INITIAL_CLI_PORT \\
    --l0--collateral 0 \\
    --l0--new-genesis-path keys/genesis.csv \\
    --l1--command \$NET_L1_COMMAND \\
    --l0--startup-port \$L0_INITIAL_PUBLIC_PORT \\
    --l0--peer-id \$L0_INITIAL_ID \\
    --l1--env dev \\
    --l1--keyalias alias \\
    --l1--password password \\
    --l1--keystore keys/l1-initial-validator-key.p12 \\
    --l1--ip \$DOCKER_HOST \\
    --l1--public-port \$L1_INITIAL_PUBLIC_PORT \\
    --l1--p2p-port \$L1_INITIAL_P2P_PORT \\
    --l1--cli-port \$L1_INITIAL_CLI_PORT \\
    --l1--l0-peer-id \$L0_INITIAL_ID \\
    --l1--l0-peer-host \$LOCALHOST \\
    --l1--l0-peer-port \$L0_INITIAL_PUBLIC_PORT \\
    --l1--collateral 0 \\
    --l1--aci-db-path aci
else
    l0_public_port=\$((9000 + \$L0_PORT_BASIS * 10))
    l0_p2p_port=\$((9001 + \$L0_PORT_BASIS * 10))
    l0_cli_port=\$((9002 + \$L0_PORT_BASIS * 10))

    l1_public_port=\$((9000 + \$L1_PORT_BASIS * 10))
    l1_p2p_port=\$((9001 + \$L1_PORT_BASIS * 10))
    l1_cli_port=\$((9002 + \$L1_PORT_BASIS * 10))

    ./write-and-join.sh \$L0_INITIAL_ID \$L0_INITIAL_HOST \$L0_INITIAL_PUBLIC_PORT \$L0_INITIAL_P2P_PORT \$LOCALHOST \$l0_public_port \$l0_cli_port &
    ./write-and-join.sh \$L1_INITIAL_ID \$L1_INITIAL_HOST \$L1_INITIAL_PUBLIC_PORT \$L1_INITIAL_P2P_PORT \$LOCALHOST \$l1_public_port \$l1_cli_port &
    java -jar combined.jar \\
        --l0--command \$NODE_COMMAND \\
        --l0--env dev \\
        --l0--keyalias alias \\
        --l0--password password \\
        --l0--keystore keys/genesis-keys/key-\$L0_PORT_BASIS.p12 \\
        --l0--l0-ip \$DOCKER_HOST \\
        --l0--ip \$DOCKER_HOST \\
        --l0--public-port \$l0_public_port \\
        --l0--p2p-port \$l0_p2p_port \\
        --l0--cli-port \$l0_cli_port \\
        --l0--collateral 0 \\
        --l1--command \$NET_L1_COMMAND \\
        --l0--startup-port \$l0_public_port \\
        --l0--peer-id \$L0_INITIAL_ID \\
        --l1--env dev \\
        --l1--keyalias alias \\
        --l1--password password \\
        --l1--keystore keys/genesis-keys/key-\$L1_PORT_BASIS.p12 \\
        --l1--ip \$DOCKER_HOST \\
        --l1--public-port \$l1_public_port \\
        --l1--p2p-port \$l1_p2p_port \\
        --l1--cli-port \$l1_cli_port \\
        --l1--l0-peer-id \$L0_INITIAL_ID \\
        --l1--l0-peer-host \$L0_INITIAL_HOST \\
        --l1--l0-peer-port \$L0_INITIAL_PUBLIC_PORT \\
        --l1--collateral 0 \\
        --l1--aci-db-path aci
fi
EOF

COPY peers/write-and-join.sh write-and-join.sh

RUN chmod +x start.sh
RUN chmod +x write-and-join.sh

CMD ["/bin/sh", "-c", "/app/start.sh"]