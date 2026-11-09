# Verification Base64 Decode Fix6

- Based on Fix4; Fix5 was used only as a reference for the Base64 decoding feature.
- Added extraction and decoding of the `url_prefix` query parameter, supporting standard Base64 and Base64URL with missing padding.
- Decoded HTTP(S) URLs are saved locally to `verification_decoded.txt`, one URL per line with no timestamps or descriptive text.
- Original `mqqapi://` URIs are saved locally to `verification_urls.txt`, one URI per line with no timestamps or descriptive text.
- Both save files skip a URL/URI that is already present, preventing repeated entries when the link has not changed.
- Decoding and decoded-link saving are optional steps and their failures do not block original URI copying or the physical Back action.
- The original `mqqapi://` URI remains the value copied to the clipboard.
- Preserves Fix4 game, six-lane music, and verification state-machine code outside the required URI handling changes.
- Decoded URLs may contain verification tokens. Keep the local output private; the app does not upload or open the decoded URL.
- Python syntax and ZIP integrity checks are performed for this package. Android Gradle compilation and real-device/cloud-device runtime testing require an Android build/runtime environment.
