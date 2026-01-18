# Hytale Soft Packets

Fixed server degradation caused by bandwidth bottlenecked servers.

## Features

* Removes network lag and jitter on low-bandwidth servers (<10 Mbps per player)
* Puts asset, chunk, and map data into a low priority queue
* Respects server bandwidth limits, user ping, UDP buffer size
* Sort packets by distance, drop outdated packets