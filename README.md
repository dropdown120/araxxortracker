# Araxxor Tracker

A comprehensive RuneLite plugin for tracking and analyzing Araxxor boss fights in Old School RuneScape. This plugin provides real-time mechanics tracking, timing analysis, performance metrics, and detailed statistics to help you improve your Araxxor kills.

## Features

### 🎯 Real-Time Fight Tracking
- **Egg Rotation Prediction**: Automatically detects and predicts the next minion spawn (⚪ White, 🔴 Red, 🟢 Green)
- **Phase Detection**: Tracks Normal, Special Attack, and Enrage phases
- **Countdown Timers**: Precise countdowns until the next egg hatch
- **Skip Detection**: Identifies when eggs are skipped and adjusts predictions accordingly

### ⏱️ Timing & Splits
- **Live Split Comparisons**: Compare your current time against:
  - Personal Best (PB)
  - Last Kill
  - Custom Target Time
- **Phase Splits**: Separate timing for Normal phase and Enrage phase
- **Visual Feedback**: Color-coded splits (green for ahead, red for behind, gold star for PB)

### 📊 Performance Metrics
- **Combat Statistics**: Track hits, average hit, DPS, and damage taken
- **Split Comparisons**: Compare current performance against best runs
- **Percentage Indicators**: See how your performance compares (e.g., "+15% hits", "-20% damage")

### 📈 Statistics & History
- **Kill Records**: Detailed history of all kills with:
  - Kill time and rotation
  - Hits, damage dealt, and damage taken
  - Loot tracking with GP value
- **Session Tracking**: Automatically groups kills into sessions
- **Best Times by Rotation**: Track your best times for each starting rotation
- **Kills Per Hour**: Calculated based on recent performance

### 🎨 Overlay Modes
- **Main Mode**: Full information display with all metrics
- **Minimal Mode**: Clean, compact display showing only essential information (rotation, time, split)

### 💎 Additional Features
- **Stats Overlay**: Optional secondary overlay showing best times and comparisons
- **Kill Box Animation**: Visual stat card displayed at boss death location
- **Loot Tracker**: Comprehensive loot tracking with custom price support
- **Config Panel**: Full-featured sidebar panel with statistics, loot history, and settings

## Configuration

### Overlay Settings
- **Show Always**: Display overlay even when not fighting Araxxor
- **Show Comparison**: Show comparison box with last kill/best time/target
- **Overlay Mode**: Choose between Main (full info) or Minimal (compact)
- **Kill Box Animation**: Enable/disable stat card at death location

### Splits & Timing
- **Show Splits**: Enable/disable live split comparisons
- **Split Type**: Choose comparison mode (Personal Best, Last Kill, or Target Time)
- **Target Time**: Set custom target time for comparison (in seconds)

### Statistics
- **Show Stats Overlay**: Enable/disable the secondary stats overlay
- **Show Rotation Best Times**: Display best times for each rotation type

## Usage

1. **Install the Plugin**: Install from the RuneLite Plugin Hub
2. **Enable the Plugin**: The plugin is enabled by default
3. **Start Fighting**: The overlay will automatically appear when you enter the Araxxor area
4. **View Statistics**: Open the sidebar panel (Araxxor icon) to view detailed statistics and loot history

### Understanding the Overlay

**During Fight:**
- **Next**: Shows the predicted next minion type with countdown timer
- **Start**: Displays the starting rotation (White/Red/Green)
- **Normal/Enrage**: Phase timers with split comparisons
- **Hits/Avg Hit/DPS**: Real-time combat performance
- **Lost HP**: Damage taken with comparison to best

**After Kill:**
- Complete fight summary with all metrics
- Split comparisons showing improvement
- Rotation indicator with colored circle

## Statistics Panel

Access the full statistics panel via the sidebar navigation button. The panel includes:

- **Overview**: Total kills, best times, and session statistics
- **Loot Tracker**: Complete loot history with GP values
- **Session Breakdown**: Kills grouped by session with totals
- **Rotation Analysis**: Best times and performance by rotation type
- **Custom Prices**: Set custom prices for loot items (useful for Noxious parts)

## Tips

- **Rotation Learning**: The plugin learns the rotation pattern after 3 eggs hatch
- **Split Tracking**: Use splits to identify which phase needs improvement
- **Session Management**: Sessions automatically reset after 45 minutes of inactivity
- **Custom Prices**: Set custom prices for Noxious weapon parts for accurate GP tracking

## Requirements

- RuneLite client
- Old School RuneScape account
- Access to Araxxor boss area

## Installation

This plugin is available through the RuneLite Plugin Hub. To install:

1. Open RuneLite
2. Go to Settings → Plugin Hub
3. Search for "Araxxor Tracker" or "Araxxor Helper"
4. Click Install

## Contributing

Contributions are welcome! If you find any bugs or have suggestions for improvements, please open an issue on the GitHub repository.

## License

This project is licensed under the BSD 2-Clause "Simplified" License.

## Support

For issues, questions, or feature requests, please visit the [GitHub repository](https://github.com/YourUsername/araxxortracker) or contact the plugin author.

---

**Note**: This plugin is not affiliated with Jagex or RuneLite. It is a community-created plugin for educational and personal use.

