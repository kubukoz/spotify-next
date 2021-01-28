let
  nixpkgs = import ../pkgs.nix;
  secrets = import ./secrets.nix;

  pkgs = import nixpkgs { };
in pkgs.mkShell {
  buildInputs = [ pkgs.netlify-cli pkgs.nodejs-14_x ];
  shellHook = "export NETLIFY_AUTH_TOKEN=${secrets.netlify.token}";
}

