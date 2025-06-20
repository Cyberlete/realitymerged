# syntax=docker/dockerfile:1.4
FROM amazoncorretto:11-alpine
ARG BUILD_VERSION=1.10.0

ENV L0_INITIAL_ID=00b8a56a20fc2e2a0196b8b8f4593ea4f736555506950103eb6fbbe435c0eeb71b32abfe21ae63bb3de8b9afdfa604bfd5837ef61e261611b8a0e5efd92ef1ea
ENV L0_INITIAL_PUBLIC_PORT=9000
ENV L0_INITIAL_P2P_PORT=9001
ENV L0_INITIAL_CLI_PORT=9002

ENV L1_INITIAL_ID=fbf91bc197ece694ae41c84903d2b965b06736cf5cc86dd78cc46107a9d9ac0ca6775d8e663b60aa015dc1ea690ed229390c0b75608521b1285f3e4775ffb8b5
ENV L1_INITIAL_PUBLIC_PORT=9010
ENV L1_INITIAL_P2P_PORT=9011
ENV L1_INITIAL_CLI_PORT=9012

WORKDIR /app
COPY kubernetes/data/ keys/
COPY modules/combined/target/scala-2.13/cyberApp-assembly-0.1.0-SNAPSHOT.jar combined.jar

RUN cat <<EOF > start.sh

set -x

export DOCKER_HOST=\$(hostname -i)
export API_HOST=\$DOCKER_HOST
export API_PORT=9100
export IPFS_PORT=5001

java -jar combined.jar \\
--l0--command run-genesis \\
--l0--env dev \\
--l0--keyalias alias \\
--l0--password password \\
--l0--keystore keys/l0-initial-validator-key.p12 \\
--l0--ip \$DOCKER_HOST \\
--l0--public-port \$L0_INITIAL_PUBLIC_PORT \\
--l0--p2p-port \$L0_INITIAL_P2P_PORT \\
--l0--cli-port \$L0_INITIAL_CLI_PORT \\
--l0--collateral 0 \\
--l0--new-genesis-path keys/genesis.csv \\
--l1--command run-initial-validator \\
--l1--env dev \\
--l1--keyalias alias \\
--l1--password password \\
--l1--keystore keys/l1-initial-validator-key.p12 \\
--l1--ip \$DOCKER_HOST \\
--l1--public-port \$L1_INITIAL_PUBLIC_PORT \\
--l1--p2p-port \$L1_INITIAL_P2P_PORT \\
--l1--cli-port \$L1_INITIAL_CLI_PORT \\
--l1--l0-peer-id \$L0_INITIAL_ID \\
--l1--l0-peer-host \$DOCKER_HOST \\
--l1--l0-peer-port \$L0_INITIAL_PUBLIC_PORT \\
--l1--collateral 0 \\
--node-api

EOF

RUN chmod +x start.sh

CMD ["/bin/sh", "-c", "/app/start.sh"]
