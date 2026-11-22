package tel.misa.mxpocket.i18n

/** Carrier-call presentation only. Caller identity, notification capabilities and routing IDs are not copy. */
object CallCopy {
    val Incoming = Copy("Incoming call", "Appel entrant", "Dolazni poziv")
    val OffHook = Copy("Dialing or active", "Sortant ou actif", "Biranje ili veza")
    val Multiple = Copy("Multiple calls", "Appels multiples", "Više poziva")
    val Call = Copy("Call", "Appel", "Poziv")
    val NoCallerDetails = Copy("Caller details unavailable", "Nom indisponible", "Ime nije dostupno")
    val LastRingCheck = Copy("Last ringing caller check: {0}", "Dernière vérification de l’appel entrant : {0}", "Poslednja provera dolaznog poziva: {0}")
    val Accept = Copy("Accept", "Accepter", "Prihvati")
    val Decline = Copy("Decline", "Refuser", "Odbij")

    val ViewTitle = Copy("Phone call", "Appel téléphonique", "Telefonski poziv")
    val ViewReason = Copy("Phone call on this device", "Appel sur cet appareil", "Poziv na ovom uređaju")
    val SettingsTitle = Copy("Phone calls", "Appels téléphoniques", "Telefonski pozivi")
    val ShowCalls = Copy("Show calls on the keypad", "Afficher les appels sur le pavé", "Prikaži pozive na tastaturi")
    val SwitchHint = Copy(
        "Show calls on the connected keypad, even when the phone is locked or the screen is off. Either page key leaves without changing the call.",
        "Afficher les appels sur le pavé connecté, même si le téléphone est verrouillé ou l’écran éteint. L’une ou l’autre touche de page quitte cet affichage sans modifier l’appel.",
        "Prikažite pozive na povezanoj tastaturi i kada je telefon zaključan ili ekran ugašen. Bilo kojim tasterom za promenu strane izlazite iz prikaza bez uticaja na poziv.")
    val RequiresTelephony = Copy(
        "Requires an Android 12 or later phone with telephony.",
        "Nécessite un téléphone sous Android 12 ou version ultérieure, compatible avec les appels mobiles.",
        "Potreban je telefon sa Androidom 12 ili novijim koji podržava pozive preko mobilne mreže.")
    val PhonePermissionHint = Copy(
        "Phone permission reads call state, not contacts or call history. No notification access is needed to show a call.",
        "L’autorisation Téléphone permet de lire l’état de l’appel, mais pas les contacts ni l’historique. L’affichage d’un appel ne nécessite pas l’accès aux notifications.",
        "Dozvola za telefon služi za čitanje stanja poziva, ne kontakata ili istorije poziva. Za prikaz poziva nije potreban pristup obaveštenjima.")
    val ConnectKeypad = Copy("Connect and authorize the USB keypad to show calls.", "Connectez le pavé USB et autorisez son accès pour afficher les appels.", "Povežite USB tastaturu i odobrite pristup za prikaz poziva.")
    val LineUnavailable = Copy("Android hasn’t reported call state.", "Android n’a pas indiqué l’état des appels.", "Android nije prijavio stanje poziva.")
    val Unpin = Copy("Unpin the layout to show the next call.", "Désépinglez la disposition pour afficher le prochain appel.", "Otkačite raspored da bi se prikazao sledeći poziv.")
    val LeaveGame = Copy("Leave the game to show the next call.", "Quittez le jeu pour afficher le prochain appel.", "Izađite iz igre da bi se prikazao sledeći poziv.")
    val WaitForIdle = Copy("Waiting for the mobile line to return to idle before showing another call.", "Le prochain appel s’affichera une fois la ligne mobile revenue au repos.", "Sledeći poziv može da se prikaže tek kada se mobilna linija oslobodi.")
    val AllowPhoneState = Copy("Allow phone state", "Autoriser la lecture de l’état des appels", "Dozvoli čitanje stanja poziva")
    val ShowDetails = Copy("Show caller and call controls", "Afficher l’appelant et les commandes d’appel", "Prikaži pozivaoca i komande za poziv")
    val DetailsHint = Copy(
        "Anyone at the keypad can see the caller’s name and picture and accept or decline, even with the phone locked, if the Phone app and Android allow it. Home hides caller details.",
        "Toute personne près du pavé peut voir le nom et la photo de l’appelant et accepter ou refuser l’appel, même sur téléphone verrouillé, si l’appli Téléphone et Android le permettent. L’accueil masque l’identité de l’appelant.",
        "Svako pored tastature može da vidi ime i sliku pozivaoca i prihvati ili odbije poziv, čak i kada je telefon zaključan, ako aplikacija Telefon i Android to dozvole. Na početnom ekranu podaci o pozivaocu su skriveni.")
    val DetailsUnavailable = Copy(
        "Caller details and controls require Android 14 or later. Use Phone on this device.",
        "Les informations sur l’appelant et les commandes nécessitent Android 14 ou version ultérieure. Sur cet appareil, utilisez l’appli Téléphone.",
        "Za podatke o pozivaocu i komande potreban je Android 14 ili noviji. Na ovom uređaju koristite aplikaciju Telefon.")
    val NotificationHint = Copy(
        "Choose MX Pocket in Android’s Notification access. Android can expose notifications from other apps. Caller details use only this profile’s default Phone app. Nothing about the caller is saved. Accept and Decline appear only if Phone supplies both actions. This choice does not enable Music or Teams.",
        "Choisissez MX Pocket dans la rubrique Accès aux notifications d’Android. Android peut exposer les notifications d’autres applis. Les informations sur l’appelant proviennent uniquement de l’appli Téléphone par défaut de ce profil et ne sont pas enregistrées. Accepter et Refuser ne s’affichent que si Téléphone fournit les deux actions. Ce choix n’active ni la musique ni Teams.",
        "U Android podešavanjima za pristup obaveštenjima izaberite MX Pocket. Android može da otkrije obaveštenja drugih aplikacija. Podaci o pozivaocu potiču samo iz podrazumevane aplikacije Telefon na ovom profilu i ne čuvaju se. Prihvati i Odbij prikazuju se samo ako Telefon nudi obe radnje. Ovaj izbor ne uključuje muziku niti Teams.")
    val ManageAccess = Copy("Manage notification access", "Gérer l’accès aux notifications", "Podesi pristup obaveštenjima")
    val AllowAccess = Copy("Allow notification access", "Autoriser l’accès aux notifications", "Dozvoli pristup obaveštenjima")

    val PhonePermissionDenied = Copy(
        "Phone state was not granted. Use Phone, or allow Phone permission in Android app settings.",
        "L’accès à l’état des appels n’a pas été accordé. Utilisez l’appli Téléphone ou accordez l’autorisation Téléphone à MX Pocket dans les paramètres Android.",
        "Pristup stanju poziva nije odobren. Koristite aplikaciju Telefon ili u Android podešavanjima odobrite aplikaciji MX Pocket dozvolu za telefon.")
    val OpenAccessSettings = Copy(
        "In Android settings, open Notification access and choose MX Pocket.",
        "Dans les paramètres Android, ouvrez Accès aux notifications et choisissez MX Pocket.",
        "U Android podešavanjima otvorite pristup obaveštenjima i izaberite MX Pocket.")
    val RestrictedAccess = Copy(
        "Android restricted notification access. Use Phone for call controls.",
        "Android a restreint l’accès aux notifications. Gérez l’appel dans l’appli Téléphone.",
        "Android je ograničio pristup obaveštenjima. Za upravljanje pozivom koristite aplikaciju Telefon.")
    val ControlsUnavailable = Copy(
        "Call controls changed or unavailable. Use Phone.",
        "Les commandes d’appel ont changé ou sont indisponibles. Utilisez l’appli Téléphone.",
        "Komande za poziv su promenjene ili nisu dostupne. Koristite aplikaciju Telefon.")
}
