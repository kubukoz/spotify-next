let
  nixpkgs = import ./pkgs.nix;
  sbt-overlay = self: super: rec {
    jre = self.jdk11;
    jdk = self.jdk11;
    scala = super.scala.override { inherit jre; };
  };
  pkgs = import nixpkgs { overlays = [ sbt-overlay ]; };
in
pkgs.mkShell { buildInputs = with pkgs;[ sbt nodejs-12_x ]; }
