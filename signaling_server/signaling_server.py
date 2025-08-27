#!/usr/bin/env python

import asyncio
import json
import logging
import websockets

logging.basicConfig(level=logging.INFO)

# In-memory storage for rooms and their participants
ROOMS = {}

async def handler(websocket):
    """
    Handle WebSocket connections, manage rooms, and relay messages.
    """
    room_id = None
    try:
        # The first message from a client should be a join message
        message = await websocket.recv()
        data = json.loads(message)

        if data.get("type") == "join":
            room_id = data.get("room")
            if not room_id:
                await websocket.send(json.dumps({"error": "Room ID is required"}))
                return

            # Add the client to the room
            if room_id not in ROOMS:
                ROOMS[room_id] = set()
            ROOMS[room_id].add(websocket)
            logging.info(f"Client {websocket.remote_address} joined room {room_id}")

            # Announce join to other clients (optional, but good for debugging)
            join_notification = {
                "type": "peer_joined",
                "peer": str(websocket.remote_address)
            }
            # websockets.broadcast is not ideal for targeted room broadcast
            # so we do it manually.
            peers_in_room = [peer for peer in ROOMS[room_id] if peer != websocket]
            if peers_in_room:
                await asyncio.gather(*[peer.send(json.dumps(join_notification)) for peer in peers_in_room])

        else:
            await websocket.send(json.dumps({"error": "First message must be of type 'join'"}))
            return

        # Listen for subsequent messages and broadcast them
        async for message in websocket:
            logging.info(f"Received message in room {room_id}: {message[:100]}...")
            # Broadcast the message to all other clients in the same room
            peers_in_room = [peer for peer in ROOMS[room_id] if peer != websocket]
            if peers_in_room:
                await asyncio.gather(*[peer.send(message) for peer in peers_in_room])

    except websockets.exceptions.ConnectionClosed as e:
        logging.info(f"Client {websocket.remote_address} disconnected. Reason: {e.code} {e.reason}")
    except json.JSONDecodeError:
        logging.warning(f"Received invalid JSON from {websocket.remote_address}")
    except Exception as e:
        logging.error(f"An error occurred with client {websocket.remote_address}: {e}")
    finally:
        # Clean up: remove the client from the room
        if room_id and room_id in ROOMS:
            ROOMS[room_id].remove(websocket)
            logging.info(f"Client {websocket.remote_address} removed from room {room_id}")
            # If the room is empty, delete it
            if not ROOMS[room_id]:
                del ROOMS[room_id]
                logging.info(f"Room {room_id} is now empty and has been closed.")
            else:
                # Notify remaining peers
                leave_notification = {
                    "type": "peer_left",
                    "peer": str(websocket.remote_address)
                }
                await asyncio.gather(*[peer.send(json.dumps(leave_notification)) for peer in ROOMS[room_id]])


async def main():
    # Start the WebSocket server
    port = 8765
    logging.info(f"Starting WebSocket signaling server on ws://0.0.0.0:{port}")
    async with websockets.serve(handler, "0.0.0.0", port):
        await asyncio.Future()  # Run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logging.info("Server shutting down.")
