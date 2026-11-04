#!/usr/bin/env bash
# Creates the release keystore ONCE. Keep the file and passwords safe forever: Mihon only accepts
# updates signed with the same key, so losing it forces every user to uninstall and reinstall.
set -euo pipefail
OUT="${1:-signingkey.jks}"
ALIAS="${ALIAS:-libraryofalexandria}"
if [ -e "$OUT" ]; then echo "$OUT already exists; refusing to overwrite" >&2; exit 1; fi
read -rsp "Keystore password: " PASS; echo
keytool -genkeypair -v -keystore "$OUT" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Library of Alexandria"
echo
echo "Add these GitHub repository secrets:"
echo "  SIGNING_KEY         = output of: base64 < $OUT | tr -d '\\n'"
echo "  ALIAS               = $ALIAS"
echo "  KEY_STORE_PASSWORD  = (the password you typed)"
echo "  KEY_PASSWORD        = (the same password)"
keytool -list -v -keystore "$OUT" -storepass "$PASS" -alias "$ALIAS" | grep -i "SHA256:"
