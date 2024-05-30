#!/bin/sh

# SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
# SPDX-License-Identifier: Apache-2.0

# details of the target being built.
target=$(jq -n \
    --arg name "$1" \
    --arg repository \
    "$URL" --arg ref \
    "$BRANCH" \
    '$ARGS.named')

# details of the workflow/pipeline repository
workflow=$(jq -n \
    --arg name "$JOB_NAME" \
    --arg repository "$GIT_URL" \
    --arg ref "$GIT_COMMIT" \
    '$ARGS.named')

# final external parameters is assembled
jq -n \
    --argjson target "$target" \
    --argjson workflow "$workflow" \
    --arg job "$JOB_NAME" \
    --arg buildRun "$BUILD_ID" \
    '$ARGS.named'
