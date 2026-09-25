# Frontend Docker deployment

Run these commands from `src/programmable-tokens-frontend/`. The Dockerfile builds a
Next.js server image on port 3000. Public configuration is compiled into the client
bundle; changing a `NEXT_PUBLIC_*` value requires a new image. In particular,
`NEXT_PUBLIC_BLOCKFROST_API_KEY` is visible to browser users and must be a key
intended for public client use.

## Build locally

Docker 20.10+ and a Blockfrost key for the selected network are required. This
example builds a Preview image without publishing it:

```bash
docker build \
  --build-arg NEXT_PUBLIC_NETWORK=preview \
  --build-arg NEXT_PUBLIC_BLOCKFROST_API_KEY=your_preview_key \
  --build-arg NEXT_PUBLIC_BLOCKFROST_URL=https://cardano-preview.blockfrost.io/api/v0 \
  --build-arg NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 \
  --build-arg NEXT_PUBLIC_BASE_URL=http://localhost:3000 \
  -t cip113-frontend:preview .
docker run --rm -p 3000:3000 cip113-frontend:preview
```

Set `NEXT_PUBLIC_API_BASE_URL` to the backend origin reachable **from the user's
browser**. The frontend adds `/api/v1` to requests; do not include that path in
the value. If the frontend is served to remote users, `localhost` refers to
their machines, so use a reachable HTTPS backend origin instead. Set
`NEXT_PUBLIC_BASE_URL` to the frontend's public origin for metadata.

The image has a health check on `/`. To inspect a running container, use
`docker ps`, `docker logs <container-id>`, or
`docker inspect --format='{{json .State.Health}}' <container-id>`.

## Build script and published images

Copy `.env.docker.example` to `.env.docker` and set the Blockfrost key and
`NEXT_PUBLIC_API_BASE_URL_<NETWORK>` for each network you build. The example file
also defines optional `NEXT_PUBLIC_BASE_URL_<NETWORK>` values. Run
`./build-docker.sh preview`, `preprod`, `mainnet`, or `all` to build images tagged
`cardanofoundation/cip113-frontend:<git-tag>-<network>` and `:<network>`.

**Current script behavior:** `build-docker.sh` passes `--push` to `docker build`
even without its optional `--push` argument. It therefore requires a configured
builder and registry credentials and may publish the image. The `--push` option
also runs separate `docker push` commands after the build. Use the manual
`docker build` command above when you only want a local image.

The repository's [GitHub Actions workflow](../../.github/workflows/docker-frontend.yml)
publishes `cardanofoundation/cip113-frontend:<git-tag>-preview` and
`:<git-tag>-preprod`, plus the moving `:preview` and `:preprod` tags. It uses
per-network Blockfrost secrets and optional repository variables for backend
and frontend URLs. Mainnet is not enabled in that workflow. The local build
script's image repository differs from the workflow's published repository.

To run a published Preview image:

```bash
docker run --rm -p 3000:3000 cardanofoundation/cip113-frontend:preview
```

The image's public configuration cannot be corrected with `docker run -e`; build
a new image for a different backend, Blockfrost key, or network.

The frontend's `docker-compose.yml` defines preview on host port 3000 and optional
preprod and mainnet profiles on ports 3001 and 3002. It runs prebuilt
`cardanofoundation/cip113-frontend` images; it does not build them or use the
`cardanofoundation` images from CI. For an image you built locally with that
tag, run:

```bash
docker compose up -d frontend-preview
docker compose --profile preprod up -d frontend-preprod
docker compose --profile mainnet up -d frontend-mainnet
docker compose down
```

For the CI-published image, use `docker run` above or change the Compose image
reference to `cardanofoundation/cip113-frontend:<network>`.

## Troubleshooting

- If the browser cannot reach the backend, check the image's
  `NEXT_PUBLIC_API_BASE_URL` build value and the backend's `/api/v1` routes.
- If an image build lacks a Blockfrost key, provide the key for that network in
  the manual build args or `.env.docker`.
- If port 3000 is occupied, map another host port, for example `-p 4000:3000`.
- For a failed health check, inspect container logs and
  `docker inspect --format='{{json .State.Health}}' <container-id>`.
