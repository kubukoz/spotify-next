let
  nixpkgs = import ./pkgs.nix;
  pkgs = import nixpkgs {};
in
pkgs.mkShell { buildInputs = with pkgs;[ sbt nodejs-12_x ]; }
