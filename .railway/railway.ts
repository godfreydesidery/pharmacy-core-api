import { defineRailway, project, service } from "railway/iac";
// This repository manages only its own resources in the environment. Other
// repositories export their own partial name.
// See https://docs.railway.com/infrastructure-as-code#multi-repo-projects
export const partial = "api";
// Restart policy: railway/iac has no option for it (docs.railway.com/infrastructure-as-code/reference,
// Nov 2026), so after the cutover from railway.toml set "Restart policy: Always" on the service in
// the Railway dashboard and check it there.
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
