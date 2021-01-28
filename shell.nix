let
  nixpkgs = import ./pkgs.nix;
  pkgs = import nixpkgs { };
in pkgs.mkShell { buildInputs = [ pkgs.sbt ]; }
