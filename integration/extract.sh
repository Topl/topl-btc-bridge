#!/bin/bash

# Extract the Id of series and group tokens from the input

while IFS= read -r line; do
    # Check if the line contains "Type" and if the next line should be read
    if [[ $line == *"Type"* && ( $line == *"Group Constructor"* || $line == *"Series Constructor"* ) ]]; then
        read_next_line=true
    elif [ "$read_next_line" = true ]; then
        # If the next line should be read and contains "Id", extract and print the Id
        if [[ $line == "Id"* ]]; then
            echo "$line" | awk '{print $NF}'
        fi
        read_next_line=false
    fi
done < "${1:-/dev/stdin}" # Read from a file if specified, otherwise from stdin