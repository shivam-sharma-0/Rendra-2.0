{
  "schemaVersion": 1,
  "mern": {
    "persistentDomainCrud": true,
    "multiPageFrontend": true,
    "accountAuthEndpoints": ["POST /api/auth/signup", "POST /api/auth/login"],
    "session": "Opaque bearer token; shared auth-client.ts provides signIn, signUp, signOut and useSession without a provider wrapper. Generated api methods attach the current token.",
    "accountOwnedData": true,
    "domainPreferences": "May use app-owned models and APIs. Session exposes userId and the entered email; do not invent account-profile APIs by default.",
    "unavailableAccountFeatures": ["password reset email", "password rotation", "account deletion", "social sign-in", "email verification", "two-factor authentication"]
  },
  "supabase": "Use only capabilities of the actual approved connected service; never infer a connection or connector approval."
}
