#!/bin/sh
cd "$(dirname "$0")" || exit 1
echo 'Use Tailscale for remote access, or a trusted private LAN. Never expose port 8765 to the internet.'
echo 'macOS: grant Terminal / Java Screen Recording and Accessibility permissions.'
printf 'Enter this computer\047s Tailscale or private LAN IPv4 address: '
read -r bind_address
exec java -jar pocketcoder-server.jar "--bind=$bind_address" --port=8765
