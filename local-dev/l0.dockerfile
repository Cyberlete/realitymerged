# syntax=docker/dockerfile:1.4
FROM amazoncorretto:11-alpine
ARG BUILD_VERSION=1.10.0
ENV CL_KEYALIAS=alias
ENV CL_PASSWORD=password
ENV CL_APP_ENV=dev
ENV LOCALHOST=0.0.0.0

ENV L0_INITIAL_ID=00b8a56a20fc2e2a0196b8b8f4593ea4f736555506950103eb6fbbe435c0eeb71b32abfe21ae63bb3de8b9afdfa604bfd5837ef61e261611b8a0e5efd92ef1ea
ENV L0_INITIAL_PUBLIC_PORT=9000
ENV L0_INITIAL_P2P_PORT=9001

WORKDIR /app

RUN apk --no-cache add curl

COPY peers/write-and-join.sh write-and-join.sh
COPY kubernetes/data/ keys/
COPY modules/combined/target/scala-2.13/cyberApp-assembly-0.1.0-SNAPSHOT.jar combined.jar

RUN cat <<EOF > start.sh

set -x

public_port=\$((9000 + \$PORT_BASIS*10))
p2p_port=\$((9001 + \$PORT_BASIS*10))
cli_port=\$((9002 + \$PORT_BASIS*10))
DOCKER_HOST=\$(hostname -i)

if [ "\$NODE_COMMAND" = "run-genesis" ]
then
    export CL_KEYSTORE=keys/l0-initial-validator-key.p12
    java -jar combined.jar run-genesis --l0-peer-id \$L0_INITIAL_ID --ip \$DOCKER_HOST --public-port \$public_port --p2p-port \$p2p_port --cli-port \$cli_port --collateral 0 keys/genesis.csv
else
    export CL_KEYSTORE=keys/genesis-keys/key-\$PORT_BASIS.p12
    ./write-and-join.sh \$L0_INITIAL_ID \$L0_INITIAL_HOST \$L0_INITIAL_PUBLIC_PORT \$L0_INITIAL_P2P_PORT \$LOCALHOST \$public_port \$cli_port  &
    java -jar combined.jar run-validator --l0-peer-id \$L0_INITIAL_ID --ip \$DOCKER_HOST --public-port \$public_port --p2p-port \$p2p_port --cli-port \$cli_port --collateral 0
fi
EOF

RUN chmod +x start.sh
RUN chmod +x write-and-join.sh

CMD ["/bin/sh", "-c", "/app/start.sh"]