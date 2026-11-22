package tel.misa.mxpocket.i18n

/** Carrier-call presentation only. Caller identity, notification capabilities and routing IDs are not copy. */
object CallCopy {
    val Incoming = Copy("Incoming call", "Appel entrant", "Dolazni poziv")
    val OffHook = Copy("Dialing or active", "Sortant ou actif", "Biranje ili veza")
    val Multiple = Copy("Multiple calls", "Appels multiples", "Više poziva")
    val UnknownCaller = Copy("Private / unknown", "Masqué / inconnu", "Skriven / nepoznat")
    val Caller = Copy("Caller", "Appelant", "Pozivalac")
    val PageKeys = Copy("Page keys", "Touches de page", "Tasteri za strane")
    val LeaveView = Copy("Leave view", "Quitter la vue", "Izađi iz prikaza")
    val Accept = Copy("Accept", "Accepter", "Prihvati")
    val Decline = Copy("Decline", "Refuser", "Odbij")
    val UsePhone = Copy("Use Phone", "Via Téléphone", "Na telefonu")
    val CallControls = Copy("Call controls", "Commandes", "Komande poziva")

    val ViewTitle = Copy("Phone call", "Appel téléphonique", "Telefonski poziv")
    val ViewReason = Copy("Phone call on this device", "Appel sur cet appareil", "Poziv na ovom uređaju")
    val SettingsTitle = Copy("Phone calls", "Appels téléphoniques", "Telefonski pozivi")
    val ShowCalls = Copy("Show calls on the keypad", "Afficher les appels sur le pavé", "Prikaži pozive na tastaturi")
    val SwitchHint = Copy(
        "On an unlocked phone, switch once per carrier call. Either page key leaves without changing the call.",
        "Si le téléphone est déverrouillé, le pavé affiche l’appel une seule fois par appel mobile. L’une ou l’autre touche de page quitte cet affichage sans modifier l’appel.",
        "Kada je telefon otključan, tastatura jednom po pozivu preko mobilne mreže prelazi na prikaz poziva. Bilo kojim tasterom za promenu strane izlazite iz prikaza bez uticaja na poziv.")
    val RequiresTelephony = Copy(
        "Requires an Android 12 or later phone with telephony.",
        "Nécessite un téléphone sous Android 12 ou version ultérieure, compatible avec les appels mobiles.",
        "Potreban je telefon sa Androidom 12 ili novijim koji podržava pozive preko mobilne mreže.")
    val PhonePermissionHint = Copy(
        "Phone permission reads call state, not contacts or call history. Automatic profile switching must be on and no layout pinned.",
        "L’autorisation Téléphone permet de lire l’état de l’appel, mais pas les contacts ni l’historique des appels. Le changement automatique de profil doit être activé et aucune disposition ne doit être épinglée.",
        "Dozvola za telefon služi za čitanje stanja poziva, ne kontakata ili istorije poziva. Automatska promena profila mora biti uključena i nijedan raspored ne sme biti zakačen.")
    val AllowPhoneState = Copy("Allow phone state", "Autoriser la lecture de l’état des appels", "Dozvoli čitanje stanja poziva")
    val ShowDetails = Copy("Show caller and call controls", "Afficher l’appelant et les commandes d’appel", "Prikaži pozivaoca i komande za poziv")
    val DetailsHint = Copy(
        "Show the Phone app's caller label and picture on the physical keypad. Anyone nearby can see them. Home hides caller details.",
        "Le pavé physique affiche le nom ou le numéro et la photo fournis par l’appli Téléphone. Toute personne à proximité peut les voir. L’accueil masque ces informations.",
        "Na fizičkoj tastaturi prikazuju se ime ili broj i slika pozivaoca iz aplikacije Telefon. Svako u blizini može da ih vidi. Na početnom ekranu ti podaci su skriveni.")
    val DetailsUnavailable = Copy(
        "Caller details and controls require Android 14 or later. Use Phone on this device.",
        "Les informations sur l’appelant et les commandes nécessitent Android 14 ou version ultérieure. Sur cet appareil, utilisez l’appli Téléphone.",
        "Za podatke o pozivaocu i komande potreban je Android 14 ili noviji. Na ovom uređaju koristite aplikaciju Telefon.")
    val NotificationHint = Copy(
        "Android notification access can expose notifications from other apps. MX Pocket uses only this profile's default Phone app call notification. Nothing about the caller is saved. Accept and Decline appear only if Phone supplies both actions.",
        "L’accès aux notifications Android peut rendre visibles les notifications d’autres applis. MX Pocket utilise uniquement la notification d’appel de l’appli Téléphone par défaut de ce profil. Aucune information sur l’appelant n’est enregistrée. Les touches Accepter et Refuser ne s’affichent que si l’appli Téléphone propose les deux actions.",
        "Pristup Android obaveštenjima može da otkrije i obaveštenja drugih aplikacija. MX Pocket koristi samo obaveštenje o pozivu iz podrazumevane aplikacije Telefon u ovom profilu. Podaci o pozivaocu se ne čuvaju. Tasteri Prihvati i Odbij prikazuju se samo ako aplikacija Telefon nudi obe radnje.")
    val ManageAccess = Copy("Manage notification access", "Gérer l’accès aux notifications", "Podesi pristup obaveštenjima")
    val AllowAccess = Copy("Allow notification access", "Autoriser l’accès aux notifications", "Dozvoli pristup obaveštenjima")

    val PhonePermissionDenied = Copy(
        "Phone state was not granted. Use Phone, or allow Phone permission in Android app settings.",
        "L’accès à l’état des appels n’a pas été accordé. Utilisez l’appli Téléphone ou accordez l’autorisation Téléphone à MX Pocket dans les paramètres Android.",
        "Pristup stanju poziva nije odobren. Koristite aplikaciju Telefon ili u Android podešavanjima odobrite aplikaciji MX Pocket dozvolu za telefon.")
    val OpenAccessSettings = Copy(
        "Open Android notification access settings for MX Pocket phone calls.",
        "Dans les paramètres Android, ouvrez l’accès aux notifications pour « Appels téléphoniques de MX Pocket ».",
        "U Android podešavanjima otvorite pristup obaveštenjima za „MX Pocket: telefonski pozivi“.")
    val RestrictedAccess = Copy(
        "Android restricted notification access. Use Phone for call controls.",
        "Android a restreint l’accès aux notifications. Gérez l’appel dans l’appli Téléphone.",
        "Android je ograničio pristup obaveštenjima. Za upravljanje pozivom koristite aplikaciju Telefon.")
    val ControlsUnavailable = Copy(
        "Call controls changed or unavailable. Use Phone.",
        "Les commandes d’appel ont changé ou sont indisponibles. Utilisez l’appli Téléphone.",
        "Komande za poziv su promenjene ili nisu dostupne. Koristite aplikaciju Telefon.")
}
