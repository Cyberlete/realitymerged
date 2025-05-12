#!/bin/bash

# Check if a configuration file argument is provided
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <config-file>"
    exit 1
fi

CONFIG_FILE="$1"

# Load resource IDs and Names from the provided config file
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Configuration file not found: $CONFIG_FILE"
    exit 1
fi

# Parse JSON with python
CLUSTER_NAME=$(python3 -c "import json; data=json.load(open('$CONFIG_FILE')); print(data['KubernetesClusterName'])")
REGISTRY_NAME=$(python3 -c "import json; data=json.load(open('$CONFIG_FILE')); print(data['RegistryName'])")

# Configure kubectl to use the new cluster's configuration
echo "Configuring kubectl for cluster: $CLUSTER_NAME..."
doctl kubernetes cluster kubeconfig save "$CLUSTER_NAME"

# Login to the container registry
echo "Logging into DigitalOcean Container Registry..."
doctl registry login

# Set environment variable for Skaffold
export PUSH_IMAGE="true"

# Run Skaffold to start the development environment
echo "Starting Skaffold..."
skaffold dev --trigger manual
