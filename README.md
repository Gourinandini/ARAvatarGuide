# 🧭 ARAvatarGuide (MITSWAY-AR)
**AI-Powered Augmented Reality Indoor Navigation System with Conversational Avatar**

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)
![ARCore](https://img.shields.io/badge/ARCore-4285F4?style=for-the-badge&logo=google&logoColor=white)
![Firebase](https://img.shields.io/badge/Firebase-FFCA28?style=for-the-badge&logo=firebase&logoColor=black)
![AI](https://img.shields.io/badge/Groq_AI-FF6F00?style=for-the-badge)

---

## 📖 Overview

**ARAvatarGuide** is an advanced **AI-powered augmented reality navigation system** designed for indoor navigation in large facilities like universities, hospitals, and corporate campuses. The system combines **ARCore spatial tracking**, **Firebase real-time path management**, **ML Kit OCR**, and **Groq AI conversational assistant** to provide an immersive, intelligent navigation experience.

Users interact with a **friendly 3D avatar guide** that responds to voice commands, answers questions, and provides turn-by-turn AR navigation with visual arrows and waypoints overlaid on the real world.

---

## ✨ Key Features

### 🎯 Core Navigation
- **AR Navigation Overlay** - 3D arrows and waypoints appear in real-world view
- **Smart Pathfinding** - Dijkstra's algorithm with path simplification for optimal routes
- **Graph-Based Floor Mapping** - Nodes and edges stored in Firebase for persistent navigation
- **Voice-Guided Navigation** - Text-to-speech provides turn-by-turn directions
- **OCR Position Recognition** - ML Kit automatically detects location from room signs

### 🤖 AI Avatar Assistant
- **Conversational AI** - Powered by Groq's Llama 3.3 70B model
- **Natural Language Understanding** - Ask questions, get directions, or chat naturally
- **Context-Aware Responses** - Remembers conversation history and current location
- **3D Animated Avatar** - Low-poly character with idle, wave, speaking, and blink animations
- **Voice Interaction** - Speech recognition for hands-free operation

### 🗺️ Path Management
- **Host Mode** - Record paths by walking through the building
- **Named Waypoints** - Mark important locations (rooms, exits, facilities)
- **Emergency Exits** - Special markers for safety routes
- **Restricted Areas** - Warnings when approaching unauthorized zones
- **Firebase Sync** - Cloud storage for floor maps and navigation graphs

### 🔒 Safety & Accessibility
- **Restricted Area Detection** - Real-time alerts with avatar warnings
- **Emergency Exit Routing** - Quick access to nearest emergency exits
- **Multi-Building Support** - Navigate across different buildings (M George, Ramanujan)
- **Floor Selection** - Choose specific floors for navigation

---

## 🏗️ Architecture

### Application Flow

```
MainActivity (Entry Point)
    ├── Host Mode → LoginActivity → ARActivity (Path Recording)
    └── Visitor Mode → BuildingActivity → PlacesActivity → FloorNavigationActivity → VisitorActivity (AR Navigation)
```

### Core Components

| Component | Purpose |
|-----------|---------|
| **MainActivity** | Entry point - choose Host or Visitor mode |
| **LoginActivity** | Password-protected access for path recording |
| **ARActivity** | Host mode - record paths, mark waypoints, OCR capture |
| **VisitorActivity** | Visitor mode - AR navigation with AI avatar |
| **BuildingActivity** | Select building (M George, Ramanujan) |
| **PlacesActivity** | Browse available locations by floor |
| **FloorNavigationActivity** | Select specific floor for navigation |

### Navigation System

| Class | Responsibility |
|-------|---------------|
| **FloorGraph** | Graph data structure with nodes and edges |
| **GraphNode** | Represents waypoints with position, name, and properties |
| **ShortestPathFinder** | Dijkstra's algorithm with path simplification |
| **PathRecorder** | Records paths while walking in Host mode |
| **NavigationHelper** | Calculates arrow positions and rotations |
| **FirebasePathManager** | Syncs floor maps to/from Firebase |

### AI & Rendering

| Class | Responsibility |
|-------|---------------|
| **GroqChatHelper** | Conversational AI using Groq API (Llama 3.3 70B) |
| **AvatarRenderer** | 3D avatar with Phong lighting and animations |
| **SimpleRenderer** | Renders waypoint spheres in AR |
| **BackgroundRenderer** | AR camera feed rendering |
| **ModelLoader** | Loads GLB models (arrow.glb, avatar.glb) |

---

## 🛠️ Technical Stack

### Core Technologies
- **Platform:** Android (API 26+)
- **Language:** Kotlin
- **AR Framework:** Google ARCore 1.41.0
- **Backend:** Firebase Realtime Database
- **AI Model:** Groq API (Llama 3.3 70B Versatile)
- **OCR:** Google ML Kit Text Recognition
- **3D Rendering:** OpenGL ES 3.0
- **Networking:** OkHttp 4.12.0

### Key Libraries
```kotlin
// ARCore for spatial tracking
implementation("com.google.ar:core:1.41.0")

// Firebase for cloud storage
implementation(platform("com.google.firebase:firebase-bom:32.7.2"))
implementation("com.google.firebase:firebase-database-ktx")

// ML Kit for OCR
implementation("com.google.mlkit:text-recognition:16.0.0")

// OkHttp for Groq API
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Material Design
implementation("com.google.android.material:material:1.11.0")
```

---

## 📋 Requirements

### Hardware
- ARCore-supported Android device
- Camera with motion sensors (gyroscope, accelerometer)
- Minimum 2GB RAM recommended

### Software
- Android 8.0 (API 26) or higher
- OpenGL ES 3.0 support
- Internet connection (for Firebase and AI features)

### API Keys
- **Groq API Key** - Required for conversational AI
- **Firebase Configuration** - `google-services.json` in `app/` directory

---

## 🚀 Getting Started

### 1️⃣ Clone the Repository
```bash
git clone https://github.com/Gourinandini/ARAvatarGuide.git
cd ARAvatarGuide
```

### 2️⃣ Configure API Keys

Create `local.properties` in the project root:
```properties
GROQ_API_KEY=your_groq_api_key_here
```

Get your free Groq API key from: https://console.groq.com/

### 3️⃣ Add Firebase Configuration

1. Create a Firebase project at https://console.firebase.google.com/
2. Add an Android app with package name: `com.example.aravatarguide`
3. Download `google-services.json`
4. Place it in `app/google-services.json`

### 4️⃣ Build and Run

```bash
./gradlew assembleDebug
```

Or open in Android Studio and click **Run** ▶️

---

## 📱 Usage Guide

### Host Mode (Path Recording)

1. Launch app and select **"Host"**
2. Enter password: `2004`
3. Enter starting point name
4. Click **"Start Recording"** and walk the path
5. Mark waypoints by clicking **"Mark Waypoint"**
6. Use **OCR** button to auto-detect room names from signs
7. Mark emergency exits and restricted areas with checkboxes
8. Click **"Stop Recording"** to save to Firebase

### Visitor Mode (Navigation)

1. Launch app and select **"Visitor"**
2. Choose building (M George or Ramanujan)
3. Browse available locations by floor
4. Select floor to start AR navigation
5. Use **voice** or **text** to ask the avatar for directions
6. Follow the **blue AR arrows** to your destination
7. Avatar provides conversational guidance and warnings

### Voice Commands Examples

- "Take me to the library"
- "Where is the principal's office?"
- "Show me the nearest exit"
- "Tell me about the CSE department"
- "How do I get to room 301?"

---

## 🗂️ Project Structure

```
ARAvatarGuide/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/aravatarguide/
│   │   │   ├── MainActivity.kt              # Entry point
│   │   │   ├── LoginActivity.kt             # Host authentication
│   │   │   ├── ARActivity.kt                # Path recording (Host)
│   │   │   ├── VisitorActivity.kt           # AR navigation (Visitor)
│   │   │   ├── BuildingActivity.kt          # Building selection
│   │   │   ├── PlacesActivity.kt            # Location browser
│   │   │   ├── FloorNavigationActivity.kt   # Floor selection
│   │   │   ├── FloorGraph.kt                # Graph data structure
│   │   │   ├── ShortestPathFinder.kt        # Pathfinding algorithm
│   │   │   ├── PathRecorder.kt              # Path recording logic
│   │   │   ├── NavigationHelper.kt          # Navigation calculations
│   │   │   ├── FirebasePathManager.kt       # Firebase sync
│   │   │   ├── GroqChatHelper.kt            # AI conversation
│   │   │   ├── AvatarRenderer.kt            # 3D avatar rendering
│   │   │   ├── SimpleRenderer.kt            # Waypoint rendering
│   │   │   ├── BackgroundRenderer.kt        # AR camera feed
│   │   │   ├── ModelLoader.kt               # GLB model loader
│   │   │   ├── Waypoint.kt                  # Waypoint data class
│   │   │   └── PathManager.kt               # Path utilities
│   │   ├── res/
│   │   │   ├── layout/                      # UI layouts
│   │   │   ├── drawable/                    # UI resources
│   │   │   └── values/                      # Colors, strings, themes
│   │   ├── assets/
│   │   │   ├── arrow.glb                    # 3D arrow model
│   │   │   └── avatar.glb                   # 3D avatar model
│   │   ├── AndroidManifest.xml
│   │   └── google-services.json             # Firebase config
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 🎨 Features in Detail

### Smart Path Simplification
Instead of following every recorded waypoint (0.3m intervals), the system simplifies paths to straight-line segments between turn points, creating a Google Maps-like navigation experience.

### Restricted Area Detection
When users approach restricted zones, the system:
- Shows avatar with warning animation
- Speaks alert message via TTS
- Displays visual notification
- Continues navigation while monitoring proximity

### OCR-Based Position Recognition
ML Kit automatically detects room numbers and names from signs, allowing the system to:
- Auto-identify starting position
- Verify current location
- Update navigation context

### Conversational AI Context
The Groq-powered avatar maintains conversation history and location context, enabling natural interactions like:
- "How is the Hinton Lab?" → Provides information
- "Take me there" → Navigates to previously mentioned location
- "What's nearby?" → Lists nearby locations

---

## 🔧 Configuration

### Firebase Database Structure
```json
{
  "floorMap": {
    "nodes": {
      "node-id-1": {
        "id": "node-id-1",
        "name": "Library",
        "position": [0.0, 0.0, 0.0],
        "isNamedWaypoint": true,
        "isEmergencyExit": false,
        "isRestrictedArea": false
      }
    },
    "adjacencyList": {
      "node-id-1": [
        {
          "from": "node-id-1",
          "to": "node-id-2",
          "distance": 2.5
        }
      ]
    },
    "timestamp": 1234567890
  }
}
```

### Groq AI System Prompt
The avatar is configured with a friendly personality and knowledge of available locations. It can:
- Provide navigation commands
- Answer general questions
- Share information about locations
- Maintain conversational context

---

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License

This project is part of an academic research initiative for indoor navigation systems.

---

## 👥 Team

Developed as part of the MITSWAY-AR project for campus navigation at Manipal Institute of Technology.

---

## 🙏 Acknowledgments

- **Google ARCore** - AR tracking and rendering
- **Firebase** - Real-time database and cloud storage
- **Groq** - Fast AI inference with Llama 3.3 70B
- **ML Kit** - Text recognition capabilities
- **Material Design** - UI components and guidelines

---

## 📞 Support

For issues, questions, or suggestions:
- Open an issue on GitHub
- Contact the development team

---

**Built with ❤️ for better indoor navigation**
