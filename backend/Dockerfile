FROM node:20-slim

# System deps + CLI OSINT tools used by the cloud endpoints.
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl unzip ca-certificates git \
 && rm -rf /var/lib/apt/lists/*

# Pinned tool versions (bump as needed).
ENV SUBFINDER_VERSION=2.6.6 \
    HTTPX_VERSION=1.6.5 \
    NUCLEI_VERSION=3.3.0 \
    TRUFFLEHOG_VERSION=3.82.0

RUN set -eux; \
    cd /tmp; \
    curl -sSL "https://github.com/projectdiscovery/subfinder/releases/download/v${SUBFINDER_VERSION}/subfinder_${SUBFINDER_VERSION}_linux_amd64.zip" -o s.zip; \
    unzip -o s.zip subfinder -d /usr/local/bin; \
    curl -sSL "https://github.com/projectdiscovery/httpx/releases/download/v${HTTPX_VERSION}/httpx_${HTTPX_VERSION}_linux_amd64.zip" -o h.zip; \
    unzip -o h.zip httpx -d /usr/local/bin; \
    curl -sSL "https://github.com/projectdiscovery/nuclei/releases/download/v${NUCLEI_VERSION}/nuclei_${NUCLEI_VERSION}_linux_amd64.zip" -o n.zip; \
    unzip -o n.zip nuclei -d /usr/local/bin; \
    curl -sSL "https://github.com/trufflesecurity/trufflehog/releases/download/v${TRUFFLEHOG_VERSION}/trufflehog_${TRUFFLEHOG_VERSION}_linux_amd64.tar.gz" -o t.tgz; \
    tar -xzf t.tgz -C /usr/local/bin trufflehog; \
    chmod +x /usr/local/bin/subfinder /usr/local/bin/httpx /usr/local/bin/nuclei /usr/local/bin/trufflehog; \
    rm -f /tmp/*.zip /tmp/*.tgz

# Pre-fetch nuclei templates so first scan is fast.
RUN nuclei -update-templates || true

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY server.js ./

ENV PORT=3000
EXPOSE 3000
CMD ["node", "server.js"]
