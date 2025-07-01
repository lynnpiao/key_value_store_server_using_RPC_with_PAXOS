# Makefile for building and running Paxos RMI system via Docker Compose

# Docker Compose up and build
up:
	docker-compose up --build

# Build images only
build:
	docker-compose build

# Bring down containers
down:
	docker-compose down

# View server logs (optional)
logs-server:
	docker logs -f rmi-server

# View client1 logs (optional)
logs-client1:
	docker logs -f rmi-client1

# View client2 logs (optional)
logs-client2:
	docker logs -f rmi-client2

# View client3 logs (optional)
logs-client3:
	docker logs -f rmi-client3

# Clean logs directory
clean-logs:
	rm -rf ./logs/*.txt

.PHONY: up build down logs-server logs-client1 logs-client2 logs-client3 clean-logs
