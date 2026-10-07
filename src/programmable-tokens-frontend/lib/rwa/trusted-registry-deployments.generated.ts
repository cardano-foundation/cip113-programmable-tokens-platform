// Generated from the backend's protocol-bootstraps-<network>.json records.
// Run npm run generate:trusted-deployments after changing a deployment record.
export const TRUSTED_REGISTRY_DEPLOYMENTS = {
  "preview": [
    {
      "txHash": "8e9668a6432ea4567bb1deba919c0f76adcce8373d6d89d1a06faee2c83d00f9",
      "registryPolicy": "e5b339ef5b16d6c460759aca1d60e5e045a6f01da4a11b4ee0fec09a"
    }
  ],
  "preprod": [
    {
      "txHash": "f4118e53fc0fac1dddf96c6dcc3b4670265f25558d9943feb4ee564488f5c896",
      "registryPolicy": "3083d387537f9318b6ff5aeab7de5f5629d26495319b8fafd409222a"
    }
  ],
  "mainnet": [
    {
      "txHash": "bfefbd222e40d88f5d4454e92b24062533070f41a3e25c0a23383264650cdb72",
      "registryPolicy": "484e733d122af44e6101988bcc47ed261a7af5c43c797e89d60e3075"
    }
  ],
  "devnet": []
} as const;
