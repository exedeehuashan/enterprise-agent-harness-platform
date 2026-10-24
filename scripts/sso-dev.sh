#!/usr/bin/env bash
# Prepares a local Keycloak for single sign-on development (P46): a realm "eahp" with the console client, EAHP's three
# roles and three test users (alice = approver, bob = operator, carol = viewer). Passwords are random and written only
# to sso/generated/ (mode 600, ignored by git). Then deploy with:  EAHP_SSO=on scripts/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."
out=sso/generated
mkdir -p "$out" && chmod 700 "$out"
umask 077
pw() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-20; }
if [ ! -f "$out/dev-users.txt" ]; then
  alice=$(pw); bob=$(pw); carol=$(pw)
  printf 'alice\t%s\teahp-approver\nbob\t%s\teahp-operator\ncarol\t%s\teahp-viewer\n' "$alice" "$bob" "$carol" > "$out/dev-users.txt"
  printf 'KC_BOOTSTRAP_ADMIN_USERNAME=admin\nKC_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$(pw)" > "$out/keycloak.env"
fi
alice=$(awk -F'\t' '$1=="alice"{print $2}' "$out/dev-users.txt")
bob=$(awk -F'\t' '$1=="bob"{print $2}' "$out/dev-users.txt")
carol=$(awk -F'\t' '$1=="carol"{print $2}' "$out/dev-users.txt")
mkdir -p "$out/import"
sed -e "s/__ALICE_PASSWORD__/$alice/" -e "s/__BOB_PASSWORD__/$bob/" -e "s/__CAROL_PASSWORD__/$carol/" sso/eahp-realm.template.json > "$out/import/eahp-realm.json"
# Keycloak reads the import as uid 1000 inside the container; widen only when this user is someone else.
if [ "$(id -u)" = "1000" ]; then chmod 700 "$out" "$out/import" && chmod 600 "$out/import/eahp-realm.json"
else chmod 755 "$out" "$out/import" && chmod 644 "$out/import/eahp-realm.json"; fi
echo "Realm written to $out/import/eahp-realm.json; test users and passwords are in $out/dev-users.txt (alice/bob/carol)."
echo "Start with: EAHP_SSO=on scripts/deploy.sh   (Keycloak: http://127.0.0.1:8180, admin password in $out/keycloak.env)"
