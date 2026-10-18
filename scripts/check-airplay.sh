#!/bin/bash
# Check that the TV still advertises and serves AirPlay (run after any change).
IP="${TV_IP:-192.168.160.128}"
code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://$IP:7000/info")
echo "AirPlay /info on :7000 -> HTTP $code"
echo "Bonjour _airplay._tcp:"
( dns-sd -B _airplay._tcp local. & p=$!; sleep 3; kill $p ) 2>/dev/null | grep -i "add" | sed 's/^/  /'
