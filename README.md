# PhoneCam: Use Your Android Phone as a High-Quality PC Webcam

This project turns your Android phone into a high-quality, low-latency webcam for your Windows PC over local Wi-Fi. It's designed for use with meeting applications like Zoom, Microsoft Teams, and Google Meet by leveraging the power of OBS Studio.

This MVP focuses on providing a stable, high-resolution (1080p @ 30fps) video stream without audio.

## Features (MVP)
- **High-Quality Video:** Streams 1080p @ 30fps video from your phone's camera.
- **Low Latency:** Optimized for local Wi-Fi networks, with target latency under 500ms.
- **Open Source:** Built entirely with free and open-source libraries.
- **WebRTC Powered:** Uses WebRTC for efficient, hardware-accelerated H.264 video encoding and secure, peer-to-peer streaming.
- **OBS Integration:** Appears as a standard webcam in any meeting software via the OBS Virtual Camera.
- **Windows PC Support:** The signaling server and viewer are designed for a Windows PC environment.

## Architecture

The system consists of three main components that work together on your local Wi-Fi network:

1.  **Android App (PhoneCam):** Captures video from the phone's camera, encodes it using H.264, and streams it over a WebRTC peer connection.
2.  **Python Signaling Server (PC):** A lightweight WebSocket server that runs on your PC. It doesn't process any video; it only relays signaling messages (like "call," "answer," and network addresses) between the phone and the PC to help them establish a direct WebRTC connection.
3.  **PC Viewer & OBS (PC):** A simple HTML page (`viewer.html`) receives the WebRTC stream in a browser view. OBS then captures this browser view and exposes it to your entire system as a "Virtual Camera," which can be selected as a video source in Zoom, Teams, etc.

```
+--------------+      (2. Signaling)      +------------------------+
|              | <----------------------> |                        |
| Android App  |      (WebSocket)         |  Python Signaling      |
| (PhoneCam)   |                          |  Server (on PC)        |
|              | <----------------------> |                        |
+--------------+                          +------------------------+
      |
      | 1. Video Stream (WebRTC, SRTP)
      |
      v
+------------------------+      3. Capture & Expose     +-----------------+
|                        | <---------------------------> |                 |
|  PC Viewer (Browser)   |     (OBS Browser Source)      |   OBS Studio    |
|  (renders video)       |                               | (Virtual Cam)   |
|                        |  ---------------------------> |                 |
+------------------------+                               +-----------------+
                                                                  | 4. Use in Apps
                                                                  v
                                                        +-------------------+
                                                        |  Zoom, Teams, etc |
                                                        +-------------------+
```

## Prerequisites

- **PC:** Windows 10/11.
- **Phone:** Android device (API 24+).
- **Network:** A stable 5 GHz Wi-Fi network is highly recommended for best performance. Both the PC and phone must be on the same network.
- **Software:**
    - **Python 3.8+:** [Download Python](https://www.python.org/downloads/)
    - **OBS Studio:** [Download OBS](https://obsproject.com/) (Make sure the Virtual Camera is included or installed).
    - **Android Studio:** [Download Android Studio](https://developer.android.com/studio) (for building and running the app).
    - A modern web browser (Chrome, Firefox, Edge).

---

## Quickstart Guide

Follow these steps to get from zero to a working wireless webcam in under 10 minutes.

### Step 1: Start the Signaling Server (on your PC)

1.  **Open a terminal** (Command Prompt, PowerShell, or Windows Terminal).
2.  **Navigate to the `signaling_server` directory:**
    ```bash
    cd path\to\project\signaling_server
    ```
3.  **Install the required Python library:**
    ```bash
    pip install -r requirements.txt
    ```
4.  **Find your PC's local IP address.** Run `ipconfig` and look for the "IPv4 Address" under your active Wi-Fi adapter (e.g., `192.168.1.101`). You will need this for the Android app.
5.  **Run the server:**
    ```bash
    python signaling_server.py
    ```
    The server is now running and listening on port `8765`. Keep this terminal window open.

### Step 2: Serve the PC Viewer Page (on your PC)

The `viewer.html` page must be accessed from a local web server.

1.  **Open a *new* terminal.**
2.  **Navigate to the `pc_viewer` directory:**
    ```bash
    cd path\to\project\pc_viewer
    ```
3.  **Start a simple Python HTTP server:**
    ```bash
    python -m http.server 8080
    ```
    This makes `viewer.html` accessible at `http://localhost:8080/viewer.html`. Keep this terminal open too.

### Step 3: Build and Run the Android App

1.  **Open Android Studio.**
2.  Select **Open** and navigate to the `android_app` directory inside the project folder. Let Gradle sync.
3.  **Connect your phone** to your PC with a USB cable and ensure USB debugging is enabled.
4.  Once the project is open, navigate to `app > src > main > java > com > example > phonecam > MainActivity.kt`.
5.  **Enter your Server URL and Room ID:** In the app's UI:
    - **Server URL:** Enter the address of your signaling server from Step 1. For example: `ws://192.168.1.101:8765`
    - **Room ID:** Enter a unique name for your room, e.g., `mycam`.
6.  **Run the app** on your connected Android device (click the green "Run" button in Android Studio).

### Step 4: Configure OBS Studio (on your PC)

1.  **Open OBS Studio.**
2.  In the **Sources** panel, click the `+` button and select **Browser**.
3.  Name the source something like "PhoneCam Viewer".
4.  In the properties dialog:
    - **URL:** `http://localhost:8080/viewer.html`
    - **Width:** `1920`
    - **Height:** `1080`
    - Click **OK**.
5.  You should now see a black rectangle in your OBS scene.
6.  In the OBS **Controls** panel (usually bottom right), click **Start Virtual Camera**.

### Step 5: Connect Everything and Go Live!

1.  **In the OBS Browser Source properties for "PhoneCam Viewer"**, right-click the source and choose **Interact**. An interactive window for the `viewer.html` page will appear.
2.  **In the `viewer.html` interaction window:**
    - The server URL should already be filled (`ws://localhost:8765`). **Change `localhost` to your PC's actual IP address** (e.g., `ws://192.168.1.101:8765`). This is because the browser source runs in a separate context.
    - **Room ID:** Enter the *exact same* Room ID you used in the Android app (e.g., `mycam`).
    - Click **Connect**.
3.  **On your phone,** tap the **Start** button.

You should see your phone's camera feed appear in the OBS scene. The OBS Virtual Camera is now broadcasting this feed to your system.

### Step 6: Verify in Zoom/Teams

1.  Open Zoom, Teams, or another meeting app.
2.  Go to video settings.
3.  In the camera selection dropdown, choose **"OBS Virtual Camera"**.
4.  Your phone's video feed should now be your webcam!

---

## Troubleshooting

- **Can't connect / No video:**
    - **Check IP Address:** Double-check that the IP address in the Android app and the OBS Browser source URL is correct.
    - **Check Room ID:** Ensure the Room ID is identical on both the app and the viewer.
    - **Firewall:** Your PC's firewall might be blocking the connection on port `8765`. Temporarily disable the firewall or add a rule to allow inbound traffic on TCP port `8765`.
    - **Same Network:** Confirm your phone and PC are on the same Wi-Fi network.

- **Debugging Logs:**
    - **Signaling Server:** The terminal running `signaling_server.py` will log connections and messages.
    - **PC Viewer:** Open the OBS **Interact** window, right-click inside, and select **Inspect**. This opens browser developer tools where you can see console logs.
    - **Android App:** Use **Logcat** in Android Studio (filtered by the tag `PhoneCam`) to see detailed logs from the app.

## Technical Notes

- **WebRTC Library:** The Android app uses the official Google WebRTC library: `org.webrtc:google-webrtc:1.0.32006`.
- **H.264 Codec:** The app attempts to force the use of the H.264 codec for better hardware acceleration and performance by manipulating the SDP (Session Description Protocol) offer/answer. This is done in the `preferH264` function in `MainActivity.kt`.
- **Security:** The signaling connection is unencrypted (`ws://`) and the stream relies on WebRTC's built-in DTLS/SRTP encryption. This setup is safe for a trusted local network but should not be exposed to the internet without securing the signaling server (using `wss://`).