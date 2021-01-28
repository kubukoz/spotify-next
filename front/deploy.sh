#!/bin/bash

nix-shell --command "npm build && netlify deploy --prod --dir build"
