let
  rev = "9ff91ce2e4c5d70551d4c8fd8830931c6c6b26b8";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "1i8ds4v6zbmnbhsrs9pby63sfbvd9sgba4w76ic9ic3lxmgy8b7z";
  };
  pkgs = import nixpkgs {};
  inherit (pkgs) fetchurl lib stdenv;
  jdk = pkgs.jdk17_headless;
in with pkgs;
mkShell {
  buildInputs = [
    babashka
    (clojure.override { jdk = jdk; })
    glibcLocales # rlwrap (used by clj) needs this
    leiningen
    rlwrap
  ];
}
