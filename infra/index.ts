import * as pulumi from "@pulumi/pulumi";
import * as digitalocean from "@pulumi/digitalocean";
import * as fs from "fs";

const config = new pulumi.Config();
const doToken = config.requireSecret("digitaloceanToken");
const environment = config.require("environment");

// Configure the DigitalOcean provider
const provider = new digitalocean.Provider(
  `reality-do-provider-${environment}`,
  {
    token: doToken,
  },
);

const registry = new digitalocean.ContainerRegistry(
  `reality-registry-${environment}`,
  {
    subscriptionTierSlug: "basic",
  },
  { provider },
);

// Create a Kubernetes cluster in DigitalOcean
const cluster = new digitalocean.KubernetesCluster(
  `reality-cluster-${environment}`,
  {
    region: "nyc1",
    version: "latest", // Automatically use the latest stable version of Kubernetes
    nodePool: {
      name: "default-pool",
      size: "s-4vcpu-8gb",
      nodeCount: 3,
    },
    registryIntegration: true
  },
  { provider },
);

// Write resource names and IDs to a JSON file after resources are provisioned
pulumi
  .all([cluster.name, cluster.id, registry.name, registry.id])
  .apply(([clusterName, clusterId, registryName, registryId]) => {
    const data = {
      KubernetesClusterName: clusterName,
      KubernetesClusterId: clusterId,
      RegistryName: registryName,
      RegistryId: registryId,
    };

    if (!fs.existsSync("tmp")) {
      fs.mkdirSync("tmp");
    }

    fs.writeFileSync(
      `tmp/resource-ids-${environment}.json`,
      JSON.stringify(data, null, 2),
    );
  });

// Export necessary data for reference
export const kubeconfig = cluster.kubeConfigs[0].rawConfig;
export const clusterName = cluster.name;
export const registryEndpoint = registry.endpoint;
