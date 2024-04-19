# Compiling using GitHub Actions
- [Obtain your api_id and api_hash](https://my.telegram.org/apps) and store them in the repository secrets as *APP\_ID* and *APP\_HASH* respectively
- Generate a key store, encode it as base64, and store it in the secrets as BAS*E64\_KEYSTORE*  
`cat your_keystore.jks | base64 -w 0`
- Store the key store password, key alias and key passowrd in the secrets as *KEYSTORE\_PASS*, *ALIAS\_NAME* and *ALIAS\_PASS* respectively
- **(Optional)** Specify the URL for an additional XML language file in the *URL_LANG* secret
- Run the action

