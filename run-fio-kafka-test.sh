#!/bin/bash
# Automated fio-based slow disk simulation + Kafka batch creation timeout test
# This script orchestrates the entire test workflow

set -e

echo "=========================================="
echo "fio + Kafka Batch Creation Timeout Test"
echo "=========================================="
echo ""

# Configuration
FIO_CONFIG=${1:-fio-slow-disk-hdd.fio}
TEST_DURATION=300  # 5 minutes
WARMUP_TIME=30     # Wait 30s for I/O pressure to build up
CONTAINER="kafka-broker"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Prerequisites check
echo "Step 1: Checking prerequisites..."
echo "=================================="

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Docker is not running${NC}"
    exit 1
fi
echo -e "${GREEN}✓${NC} Docker is running"

# Check if kafka-broker container exists and is running
if ! docker ps | grep -q $CONTAINER; then
    echo -e "${YELLOW}WARNING: kafka-broker container not running${NC}"
    echo "Starting containers with docker-compose..."
    docker-compose up -d
    echo "Waiting 30s for Kafka to start..."
    sleep 30
fi
echo -e "${GREEN}✓${NC} kafka-broker container is running"

# Check if fio is available in the container
if ! docker exec $CONTAINER which fio > /dev/null 2>&1; then
    echo -e "${YELLOW}WARNING: fio not installed in container${NC}"
    echo "Installing fio in kafka-broker container..."
    docker exec -u root $CONTAINER bash -c "apt-get update -qq && apt-get install -y -qq fio" || {
        echo -e "${RED}ERROR: Failed to install fio. Container might not support apt-get.${NC}"
        echo "Try manually: docker exec -u root kafka-broker bash -c 'apt-get update && apt-get install -y fio'"
        exit 1
    }
fi
echo -e "${GREEN}✓${NC} fio is available"

# Check if fio config file exists
if [ ! -f "$FIO_CONFIG" ]; then
    echo -e "${RED}ERROR: fio config file not found: $FIO_CONFIG${NC}"
    echo "Available configs:"
    ls -1 fio-*.fio 2>/dev/null || echo "  No fio config files found"
    exit 1
fi
echo -e "${GREEN}✓${NC} fio config file found: $FIO_CONFIG"

echo ""

# Step 2: Copy fio config into container
echo "Step 2: Copying fio config to container..."
echo "==========================================="
docker cp $FIO_CONFIG $CONTAINER:/tmp/fio-test.fio
echo -e "${GREEN}✓${NC} Config copied to container:/tmp/fio-test.fio"
echo ""

# Step 3: Start baseline monitoring
echo "Step 3: Checking baseline disk performance..."
echo "==============================================="
echo "Running quick 10-second baseline test..."
docker exec $CONTAINER fio --name=baseline --filename=/var/lib/kafka/data/baseline.dat \
    --size=100M --bs=4k --rw=randwrite --direct=1 --numjobs=1 --runtime=10 --time_based=1 \
    --output-format=normal | grep -E "write:|iops|lat.*avg"
echo ""
echo -e "${GREEN}✓${NC} Baseline test complete"
echo ""

# Step 4: Start fio I/O pressure in background
echo "Step 4: Starting fio I/O pressure..."
echo "====================================="
echo "Config: $FIO_CONFIG"
echo "Duration: ${TEST_DURATION}s"
echo ""

# Run fio in background and save PID
docker exec -d $CONTAINER fio /tmp/fio-test.fio
sleep 2

# Verify fio is running
FIO_COUNT=$(docker exec $CONTAINER ps aux | grep -c '[f]io /tmp' || true)
if [ "$FIO_COUNT" -eq 0 ]; then
    echo -e "${RED}ERROR: fio failed to start${NC}"
    exit 1
fi
echo -e "${GREEN}✓${NC} fio started successfully ($FIO_COUNT process(es))"
echo ""

# Step 5: Monitor I/O metrics
echo "Step 5: Monitoring I/O metrics (waiting ${WARMUP_TIME}s for pressure to build)..."
echo "================================================================================="
echo ""

# Monitor for warmup period
for i in $(seq 1 $WARMUP_TIME); do
    if [ $((i % 5)) -eq 0 ] || [ $i -le 5 ]; then
        # Check I/O stats every 5 seconds (or first 5 seconds)
        echo "[$i/${WARMUP_TIME}s] Checking disk I/O in container..."
        docker exec $CONTAINER bash -c 'cat /proc/diskstats | head -5' || true
    fi
    sleep 1
done

echo ""
echo -e "${GREEN}✓${NC} I/O pressure should now be elevated"
echo ""

# Step 6: Run Kafka producer test
echo "Step 6: Running Kafka producer test..."
echo "======================================="
echo "This will attempt to send messages while disk I/O is saturated"
echo ""

# Compile and run the test
mvn -q compile
mvn -q exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationProgressiveIO" -Dexec.args="5" || {
    echo ""
    echo -e "${YELLOW}Test execution completed (may have intentional failures)${NC}"
}

echo ""

# Step 7: Check final I/O status
echo "Step 7: Checking final I/O status..."
echo "====================================="
FIO_STILL_RUNNING=$(docker exec $CONTAINER ps aux | grep -c '[f]io /tmp' || true)
if [ "$FIO_STILL_RUNNING" -gt 0 ]; then
    echo -e "${YELLOW}fio is still running ($FIO_STILL_RUNNING process(es))${NC}"
    echo "Remaining test time: ~$((TEST_DURATION - WARMUP_TIME - 60))s"
else
    echo -e "${GREEN}fio has completed${NC}"
fi
echo ""

# Step 8: Cleanup
echo "Step 8: Cleanup..."
echo "=================="
read -p "Stop fio processes and clean up test files? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Stopping fio processes..."
    docker exec $CONTAINER pkill fio 2>/dev/null || echo "  No fio processes running"

    echo "Removing test files..."
    docker exec $CONTAINER rm -f /var/lib/kafka/data/fio-test*.dat
    docker exec $CONTAINER rm -f /var/lib/kafka/data/baseline.dat
    docker exec $CONTAINER rm -f /tmp/fio-test.fio

    echo -e "${GREEN}✓${NC} Cleanup complete"
else
    echo "Cleanup skipped. To clean up manually:"
    echo "  docker exec $CONTAINER pkill fio"
    echo "  docker exec $CONTAINER rm -f /var/lib/kafka/data/fio-test*.dat"
fi

echo ""
echo "=========================================="
echo "Test workflow complete!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Review the Kafka test output above for 'batch creation' timeout errors"
echo "2. If no timeout occurred, try with more aggressive config: fio-slow-disk-extreme.fio"
echo "3. Monitor actual svctm values using: ./monitor-svctm.sh"
echo ""
