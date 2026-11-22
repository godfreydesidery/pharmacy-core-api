import { defineRailway, project, service } from "railway/iac";
// This repository manages only its own resources in the environment. Other
// repositories export their own partial name.
// See https://docs.railway.com/infrastructure-as-code#multi-repo-projects
export const partial = "api";
export default defineRailway(() => {
  const api = service("api", {
    build: { buildEnvironment: "V3", builder: "DOCKERFILE", dockerfilePath: "Dockerfile" },
    healthcheck: "/actuator/health/readiness",
    healthcheckTimeout: 300,
    replicas: { "iad": 1 },
  });

  return project("pharmacy-core-test", {
    variables: { managed: false },
    resources: [api],
  });
});
