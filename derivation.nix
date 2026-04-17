{ mkSbtDerivation, gitignore-source, which, clang, s2n-tls }:

let pname = "spotify-next"; in

mkSbtDerivation {
  inherit pname;
  version = "0.1.0";
  depsSha256 = "sha256-5eu3lgUw9o0phd/LL4V+P8XP/8uSqhToi/DonqlCBQk=";

  buildInputs = [ which clang ];
  nativeBuildInputs = [ s2n-tls ];
  depsWarmupCommand = ''
    sbt compile
  '';

  src = gitignore-source.lib.gitignoreSource ./.;

  buildPhase = ''
    sbt appNative/nativeLink
  '';

  installPhase = ''
    mkdir -p $out/bin
    cp app/.native/target/scala-3.7.3/spotify-next $out/bin/$pname
  '';
}
