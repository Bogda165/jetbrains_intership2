#!/bin/bash
set -e

echo "Setting up permission test environment..."

# Create test directory
TEST_DIR=$(mktemp -d)
echo "Created test directory: $TEST_DIR"

# Create test files with different permissions
echo "Creating test files..."
mkdir -p "$TEST_DIR/readable"
echo "This is readable" > "$TEST_DIR/readable/file1.txt"
echo "This is also readable" > "$TEST_DIR/readable/file2.txt"

# Create protected directory with restricted permissions
mkdir -p "$TEST_DIR/protected"
echo "Secret data" > "$TEST_DIR/protected/secret.txt"
echo "More secrets" > "$TEST_DIR/protected/confidential.txt"

# Create a test user
TEST_USER="testuser2"
echo "Creating test user: $TEST_USER"
sudo useradd -m $TEST_USER

# Set permissions
echo "Setting permissions..."
chmod 755 "$TEST_DIR"
chmod 755 "$TEST_DIR/readable"
chmod 644 "$TEST_DIR/readable/"*
chmod 700 "$TEST_DIR/protected"  # Only owner can access
chmod 600 "$TEST_DIR/protected/"*

# Make sure the current user owns everything
sudo chown -R $(whoami):$(whoami) "$TEST_DIR"

# Copy the JAR to the test directory
cp to_run.jar "$TEST_DIR/"
chmod 755 "$TEST_DIR/to_run.jar"

# Create a runner script that the test user can execute
cat > "$TEST_DIR/run_test.sh" << 'EOF'
#!/bin/bash
echo "Running as: $(whoami)"
echo "Testing with permission denied handling..."
java -jar to_run.jar "$(pwd)"
EOF

chmod 755 "$TEST_DIR/run_test.sh"

# Change directory to test dir for execution
cd "$TEST_DIR"

# First run as current user to see everything accessible
echo "First running as current user (should see all files):"
java -jar to_run.jar "$TEST_DIR"

# Then run as test user to test permission denied handling
echo -e "\nNow running as test user (should handle permission denied):"
sudo -u $TEST_USER bash -c "cd $TEST_DIR && ./run_test.sh"

# Cleanup
echo -e "\nCleaning up..."
cd /tmp
sudo userdel -r $TEST_USER
rm -rf "$TEST_DIR"

echo "Test complete!"