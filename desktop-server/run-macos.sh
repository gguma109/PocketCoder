#!/bin/sh
cd "$(dirname "$0")" || exit 1
echo 'Trusted private Wi-Fi ONLY. Do not expose port 8765 to the internet.'
echo 'macOS: grant Terminal / Java Screen Recording and Accessibility permissions.'
printf 'Enter this computer\047s private LAN IPv4 address: '
read -r bind_address
exec java -jar pocketcoder-server.jar "--bind=$bind_address" --port=8765
