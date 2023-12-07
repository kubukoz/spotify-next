{ mkSbtDerivation, gitignore-source, which, clang, s2n-tls }:

let pname = "spotify-next"; in

mkSbtDerivation {
  inherit pname;
  version = "0.1.0";
  depsSha256 = "sha256-nPDkvJR87D1iiXIpivZCEB+yx4pE04PtjFd8rcNH54Q=";

  buildInputs = [ which clang ];
  nativeBuildInputs = [ s2n-tls ];
  depsWarmupCommand = ''
    sbt compile
  '';

  src = gitignore-source.lib.gitignoreSource ./.;

  buildPhase = ''
    sbt nativeLink
  '';

  installPhase = ''
    mkdir -p $out/bin
    cp app/.native/target/scala-3.3.1/spotify-next-out $out/bin/$pname
  '';
}
