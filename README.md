# reality

## Running L0 & L1 in Kubernetes

### Prerequisites

1. [sbt](https://www.scala-sbt.org/)
2. [Docker Desktop](https://www.docker.com/get-started/) with [Kubernetes](https://docs.docker.com/desktop/kubernetes/) enabled
3. [Skaffold CLI](https://skaffold.dev/docs/install/#standalone-binary)

### Starting clusters

```
skaffold dev --trigger=manual
```

This will start both L0 and L1 clusters on kubernetes using current kube-context.

Initial validators for L0 and L1 have their public ports mapped to local ports 9000 and 9010 respectively.

```
curl localhost:9000/cluster/info
curl localhost:9010/cluster/info
```

This will return a list of validators on L0 and L1. By default, both L0 and L1 clusters starts with 3 validators
(1 initial and 2 regular).

### Start Cluster with Nix

### L0

#### Initial Validator

```
export CL_KEYSTORE=kubernetes/data/l0-initial-validator-key.p12
export CL_KEYALIAS=alias
export CL_PASSWORD=password
export CL_SNAPSHOT_STORED_PATH=data/snapshot-0

nix-shell shell.nix

# modules/core/src/main/scala/org/reality/cli/method.scala for details

java -jar modules/core/target/scala-2.13/reality-core-assembly-1.10.0-SNAPSHOT.jar run-genesis --ip 127.0.0.1 --public-port 9000 --p2p-port 9001 --cli-port 9002 --collateral 0 --env dev kubernetes/data/genesis.csv

```

#### Validator

```
# other env vars same as above
export CL_KEYSTORE=kubernetes/data/genesis-keys/key-0.p12
export CL_SNAPSHOT_STORED_PATH=data/snapshot-2

java -jar modules/core/target/scala-2.13/reality-core-assembly-1.10.0-SNAPSHOT.jar run-validator --ip 127.0.0.1 --public-port 9020 --p2p-port 9021 --cli-port 9022 --collateral 0 --env dev

# connect to peers
sh peers/join.sh 127.0.0.1:9022 peers/l0.json
```

---

### L1

#### Initial Validator

```
export CL_KEYSTORE=kubernetes/data/l1-initial-validator-key.p12
export CL_KEYALIAS=alias
export CL_PASSWORD=password

nix-shell shell.nix

#modules/dag-l1/src/main/scala/org/reality/dag/l1/cli/method.scala for details

java -jar modules/dag-l1/target/scala-2.13/reality-dag-l1-assembly-1.10.0-SNAPSHOT.jar run-initial-validator --env dev --ip 127.0.0.1 --public-port 9010 --p2p-port 9011 --cli-port 9012 --l0-peer-id=00b8a56a20fc2e2a0196b8b8f4593ea4f736555506950103eb6fbbe435c0eeb71b32abfe21ae63bb3de8b9afdfa604bfd5837ef61e261611b8a0e5efd92ef1ea --l0-peer-host=127.0.0.1 --l0-peer-port=9000 --collateral 0

```

#### Validator

```
# other env vars same as above
export CL_KEYSTORE=kubernetes/data/genesis-keys/key-1.p12

java -jar modules/dag-l1/target/scala-2.13/reality-dag-l1-assembly-1.10.0-SNAPSHOT.jar run-validator --env dev --ip 127.0.0.1 --public-port 9030 --p2p-port 9031 --cli-port 9032 --l0-peer-id=00b8a56a20fc2e2a0196b8b8f4593ea4f736555506950103eb6fbbe435c0eeb71b32abfe21ae63bb3de8b9afdfa604bfd5837ef61e261611b8a0e5efd92ef1ea --l0-peer-host=127.0.0.1 --l0-peer-port=9000 --collateral 0

# connect to peers
chmod +x peers/join.sh
sh peers/join.sh 127.0.0.1:9032 peers/l1.json
```

### Scaling a cluster

```

kubectl scale deployment/l0-validator-deployment --replicas=9

```

This scales the L0 cluster to 10 validators total: 1 initial and 9 regular.

### Docker Compose

```
sbt assembly
docker-compose up
```

### Native Image

```
native-image -H:ConfigurationFileDirectories=./graal-agent -jar ./modules/core/target/scala-2.13/reality-core-assembly-1.10.0-SNAPSHOT.jar
### Pulumi

```

# Create resources from infra directory

cd infra && pulumi up

# Will write resource values to infra/tmp/resource-ids-${ENVIRONMENT}.json

# In project root run script to push docker images and configure kubernetes

./configure-environment.sh infra/tmp/resource-ids-${ENVIRONMENT}.json

```

```
