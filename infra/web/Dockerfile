# syntax=docker/dockerfile:1
# Web pública en modo instantáneas (fase 6.3.5): el build de instantáneas (npm run build:snapshot)
# servido por Node como usuario sin privilegios. El build convencional no se toca.
#
# Contexto: la raíz del repositorio (infra/local/compose.yaml lo fija). Lo que entra en el
# contexto lo limita Dockerfile.dockerignore (lista de permitidos).
#
# Dependencias en ejecución (comprobado en la 6.3.5): el servidor generado
# (dist/…/server/server.mjs) está empaquetado y solo importa módulos internos de Node (fs, path,
# crypto, url, stream, process, node:module). La etapa final no lleva node_modules, npm, npx ni
# corepack: solo Node y el resultado del build.

# Imagen base fijada por digest (misma versión que .node-version y engines de package.json).
ARG NODE_IMAGE=node:26.13.0-trixie-slim@sha256:5adad64f9a1fca2d51972caa65983634c8a774eb300d72b20f7f2a0f60368072

# --- 1. Build -----------------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS build
WORKDIR /build

# npm del equipo de desarrollo (11.6.2), con el que se generó package-lock.json. El npm de la
# imagen (11.20.0) es más estricto con los peers opcionales y rechaza ese lockfile (faltan
# @emnapi/core y @emnapi/runtime, peers opcionales de @napi-rs/wasm-runtime); fijar la herramienta
# evita tocar el lockfile y el build convencional. Solo en esta etapa: la final no lleva npm.
ARG NPM_VERSION=11.6.2
RUN npm install --global --no-audit --no-fund npm@${NPM_VERSION}

# Dependencias primero: la capa se reutiliza mientras no cambie el lockfile.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY angular.json tsconfig.json tsconfig.app.json ./
COPY public ./public
COPY src ./src
RUN npm run build:snapshot

# --- 2. Ejecución -------------------------------------------------------------------------------
FROM ${NODE_IMAGE}

# Fuera las herramientas de desarrollo y gestión de paquetes que trae la imagen base.
RUN rm -rf /usr/local/lib/node_modules /usr/local/include/node /opt/yarn-* \
      /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
      /usr/local/bin/yarn /usr/local/bin/yarnpkg

WORKDIR /app
# Propiedad de root y solo lectura para el usuario node: el servidor no puede modificar su código.
COPY --from=build /build/dist/cristian-atienza-instantaneas/ ./

USER node
ENV NODE_ENV=production \
    PORT=4000
EXPOSE 4000

ENTRYPOINT ["node", "server/server.mjs"]
