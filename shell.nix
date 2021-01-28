let
  nixpkgs = import ./pkgs.nix;
  pkgs = import nixpkgs {
    overlays = let
      java = (self: super: rec {
        jdk = super.jdk11;
        jre = jdk;
      });
    in [ java ];
  };
in pkgs.mkShell { buildInputs = [ pkgs.sbt ]; }
