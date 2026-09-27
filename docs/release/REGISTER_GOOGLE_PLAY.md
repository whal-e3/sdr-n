# Register a Google Play developer account

These are account-owner steps. Local build/signing work does not need a Play
account. Do not send identity documents, card numbers or passwords in chat.

1. Open https://play.google.com/console/signup in your browser and sign in to
   the Google account you intend to keep as the developer-account owner.
   Google requires the owner to be at least 18.
2. Choose **Personal** if publishing as yourself. Choose **Organization** only
   for an actual organization and follow its separate organization-verification
   requirements. This hobby app does not itself require an organization account.
3. Read and accept the Google Play Developer Distribution Agreement.
4. Enter the requested developer/contact details. Use your real legal identity
   for verification, and choose the public developer display name separately.
   Inspect the form's explanation of which name/contact/address fields are public.
   Verify the email and phone number when requested. A dedicated working support
   email is useful for the store listing and privacy questions.
5. Pay the **one-time US$25 developer-account registration fee** using an
   accepted credit/debit card. It registers the account, not each app. Google
   says prepaid cards are not accepted; available payment methods vary by country.
6. Complete the identity-verification tasks shown on the Console dashboard.
   Google may request a government ID and a card in your legal name. Submit
   those directly through Google's verification flow.
7. For a new personal account, complete the Android-device verification task
   using the **Google Play Console** mobile app and the same owner account.
   Follow the dashboard's instructions on the real phone.
8. When the account is verified, choose **Create app**, enter **OrbitScope**,
   choose the language, **App**, and the intended free/paid status, then complete
   the declarations shown. Decide these choices deliberately before submission.
9. Use the prepared listing, graphics and screenshots in `store/`. Provide the
   owner's support email and the final public privacy URL. Review the worksheet
   in `PLAY_DECLARATIONS.md`; answer app-content forms for the exact release.
10. Start with **Testing → Internal testing**. Configure **Play App Signing**,
    upload the signed AAB, add testers and check installation/pre-launch reports.
    Resolve the release blockers in PUBLISHING.md before advertising satellite
    reception. The upload key is already separate from the catalog-signing key.
11. A personal account created now must run a **closed test with at least
    12 testers continuously opted in for 14 days**, then apply for production
    access. Internal testing alone does not meet this requirement. Keep notes
    about tester activity, feedback and fixes for the access application.
12. After production access and review approval, choose a production rollout.
    Account registration does not automatically publish an app.

The Console may show these verification tasks in a different order. Follow its
current dashboard and official instructions:

- Registration: https://support.google.com/googleplay/android-developer/answer/6112435?hl=en
- Testing requirement: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en
- App signing: https://developer.android.com/studio/publish/app-signing
