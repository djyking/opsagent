FROM node:22-alpine AS builder

RUN corepack enable
WORKDIR /web
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile || pnpm approve-builds esbuild
RUN pnpm install --frozen-lockfile
COPY . .
RUN ./node_modules/.bin/vue-tsc -b && ./node_modules/.bin/vite build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /web/dist /usr/share/nginx/html
EXPOSE 80
