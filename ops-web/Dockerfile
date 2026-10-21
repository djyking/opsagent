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
RUN chown -R root:root /usr/share/nginx/html \
    && find /usr/share/nginx/html -type d -exec chmod 0755 {} + \
    && find /usr/share/nginx/html -type f -exec chmod 0644 {} + \
    && chmod 0644 /etc/nginx/conf.d/default.conf
EXPOSE 80
