FROM node:20-slim

# Install sonar-scanner and Java
RUN apt-get update && apt-get install -y \
    openjdk-17-jre-headless \
    curl \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Install sonar-scanner CLI
RUN curl -sSL https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-5.0.1.3006-linux.zip -o /tmp/sonar-scanner.zip \
    && unzip /tmp/sonar-scanner.zip -d /opt \
    && mv /opt/sonar-scanner-5.0.1.3006-linux /opt/sonar-scanner \
    && rm /tmp/sonar-scanner.zip

ENV PATH="/opt/sonar-scanner/bin:${PATH}"

WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .

RUN mkdir -p /tmp/uploads /tmp/projects

EXPOSE 3001
CMD ["node", "server.js"]
