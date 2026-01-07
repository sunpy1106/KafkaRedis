#!/bin/bash
# Monitor svctm and await metrics from iostat
# Usage: ./monitor-svctm.sh [device] [interval_seconds]

DEVICE=${1:-sda}
INTERVAL=${2:-5}

echo "========== Monitoring disk I/O metrics =========="
echo "Device: $DEVICE"
echo "Interval: ${INTERVAL}s"
echo "Metrics: svctm (service time), await (average wait time), %util (utilization)"
echo ""
echo "ALM-12033 Thresholds:"
echo "  HDD: svctm > 1000ms (critical) or > 150ms sustained (warning)"
echo "  SSD: svctm > 1000ms (critical) or > 20ms sustained (warning)"
echo ""
echo "Press Ctrl+C to stop"
echo "=================================================="
echo ""

# Check if iostat is available
if ! command -v iostat &> /dev/null; then
    echo "ERROR: iostat not found. Installing sysstat..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        echo "On macOS, use: brew install sysstat"
    else
        sudo apt-get update && sudo apt-get install -y sysstat
    fi
    exit 1
fi

# Print header
printf "%-19s  %10s  %10s  %10s  %10s\n" "Timestamp" "svctm(ms)" "await(ms)" "avgqu-sz" "%util"
printf "%s\n" "--------------------------------------------------------------------------------"

# Continuous monitoring
while true; do
    # Use iostat to get disk stats
    # -x: extended statistics
    # -d: display device utilization
    # -m: megabytes
    iostat -x -d $INTERVAL 1 | awk -v dev="$DEVICE" '
    /^'$DEVICE'/ || /^disk0/ {
        # Different output format on Linux vs macOS
        # Linux: Device r/s w/s ... await ... svctm %util
        # macOS: disk0  KB/t  tps  MB/s (no svctm in macOS iostat)
        timestamp = strftime("%Y-%m-%d %H:%M:%S")

        # Try to extract metrics (Linux format)
        svctm = $13      # service time (ms) - column 13 on Linux
        await = $10      # average wait time (ms)
        avgqu = $9       # average queue size
        util = $14       # % utilization

        # If svctm is empty or 0, it might be macOS (which doesn'\''t provide svctm)
        if (svctm == "" || svctm == 0) {
            printf "%-19s  %10s  %10s  %10s  %10s\n", timestamp, "N/A(macOS)", "N/A", "N/A", "N/A"
        } else {
            printf "%-19s  %10.2f  %10.2f  %10.2f  %10.2f\n", timestamp, svctm, await, avgqu, util

            # Alert on high svctm
            if (svctm > 1000) {
                printf "  🚨 CRITICAL: svctm > 1000ms (ALM-12033 trigger condition!)\n" > "/dev/stderr"
            } else if (svctm > 150) {
                printf "  ⚠️  WARNING: svctm > 150ms (HDD threshold)\n" > "/dev/stderr"
            } else if (svctm > 20) {
                printf "  ⚠️  WARNING: svctm > 20ms (SSD threshold)\n" > "/dev/stderr"
            }
        }
    }'

    # Small delay to prevent output duplication
    sleep 0.1
done
