import sys
import json
import easyocr
import re

def detect_document_type(text_blocks):
    """Détecte le type de document basé sur les mots-clés internationaux"""
    text_combined = ' '.join([block for block in text_blocks]).upper()

    # Mots-clés pour passeports
    passport_keywords = [
        'PASSPORT', 'PASSEPORT', 'PASSAPORTO', 'REISEPASS', 'PASAPORTE',
        'PASZPORT', 'PASSAPORTE', 'ПАСПОРТ', 'جواز سفر'
    ]

    # Mots-clés pour cartes d'identité
    id_card_keywords = [
        'IDENTITY CARD', 'CARTE IDENTITE', 'AUSWEIS', 'DOCUMENTO IDENTIDAD',
        'CARTA IDENTITA', 'DOWOD OSOBISTY', 'УДОСТОВЕРЕНИЕ', 'بطاقة هوية',
        'NATIONAL ID', 'CITIZEN ID', 'RESIDENT CARD'
    ]

    # Mots-clés pour permis de résidence
    residence_keywords = [
        'RESIDENCE PERMIT', 'TITRE SEJOUR', 'AUFENTHALTSTITEL',
        'PERMESSO SOGGIORNO', 'TARJETA RESIDENCIA', 'РАЗРЕШЕНИЕ'
    ]

    if any(keyword in text_combined for keyword in passport_keywords):
        return 'PASSPORT'
    elif any(keyword in text_combined for keyword in id_card_keywords):
        return 'ID_CARD'
    elif any(keyword in text_combined for keyword in residence_keywords):
        return 'RESIDENCE_PERMIT'
    else:
        return 'UNKNOWN'

def detect_country(text_blocks):
    """Détecte le pays d'émission du document"""
    text_combined = ' '.join([block for block in text_blocks]).upper()

    countries = {
        'FRANCE': ['REPUBLIQUE FRANCAISE', 'FRANCE', 'FRENCH REPUBLIC'],
        'GERMANY': ['BUNDESREPUBLIK DEUTSCHLAND', 'GERMANY', 'DEUTSCHLAND'],
        'SPAIN': ['REINO DE ESPANA', 'SPAIN', 'ESPANA'],
        'ITALY': ['REPUBBLICA ITALIANA', 'ITALY', 'ITALIA'],
        'UK': ['UNITED KINGDOM', 'GREAT BRITAIN', 'ROYAUME-UNI'],
        'USA': ['UNITED STATES', 'USA', 'AMERICA'],
        'CANADA': ['CANADA'],
        'MOROCCO': ['ROYAUME DU MAROC', 'MOROCCO', 'المغرب'],
        'ALGERIA': ['REPUBLIQUE ALGERIENNE', 'ALGERIA', 'الجزائر'],
        'TUNISIA': ['REPUBLIQUE TUNISIENNE', 'TUNISIA', 'تونس'],
        'SENEGAL': ['REPUBLIQUE DU SENEGAL', 'SENEGAL'],
        'MALI': ['REPUBLIQUE DU MALI', 'MALI'],
        'BURKINA_FASO': ['BURKINA FASO'],
        'IVORY_COAST': ['COTE D\'IVOIRE', 'IVORY COAST']
    }

    for country, keywords in countries.items():
        if any(keyword in text_combined for keyword in keywords):
            return country

    return 'UNKNOWN'

def extract_dates(text):
    """Extrait les dates dans différents formats internationaux"""
    date_patterns = [
        # Format européen : DD/MM/YYYY, DD.MM.YYYY, DD-MM-YYYY
        r'\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})\b',
        # Format américain : MM/DD/YYYY
        r'\b(\d{1,2})/(\d{1,2})/(\d{4})\b',
        # Format ISO : YYYY-MM-DD
        r'\b(\d{4})-(\d{1,2})-(\d{1,2})\b',
        # Format avec espaces : DD MM YYYY
        r'\b(\d{1,2})\s+(\d{1,2})\s+(\d{4})\b'
    ]

    dates_found = []
    for pattern in date_patterns:
        matches = re.finditer(pattern, text)
        for match in matches:
            groups = match.groups()
            # Essayer de normaliser au format DD/MM/YYYY
            if len(groups) == 3:
                if len(groups[0]) == 4:  # Format YYYY-MM-DD
                    year, month, day = groups
                    dates_found.append(f"{day.zfill(2)}/{month.zfill(2)}/{year}")
                else:  # Autres formats
                    part1, part2, year = groups
                    dates_found.append(f"{part1.zfill(2)}/{part2.zfill(2)}/{year}")

    return dates_found

def extract_document_numbers(text):
    """Extrait les numéros de documents (patterns génériques)"""
    number_patterns = [
        # Numéros alphanumériques (8-15 caractères)
        r'\b([A-Z0-9]{8,15})\b',
        # Numéros avec espaces ou tirets
        r'\b([A-Z0-9]{2,4}[\s-][A-Z0-9]{2,4}[\s-][A-Z0-9]{2,4})\b',
        # Numéros purement numériques (6-12 chiffres)
        r'\b(\d{6,12})\b'
    ]

    numbers_found = []
    for pattern in number_patterns:
        matches = re.finditer(pattern, text)
        for match in matches:
            num = match.group(1)
            # Filtrer les dates et autres patterns non pertinents
            if not re.match(r'^\d{2}/\d{2}/\d{4}$', num) and not re.match(r'^\d{4}$', num):
                numbers_found.append(num)

    return numbers_found

def extract_names(text_blocks):
    """Extrait les noms en utilisant des patterns génériques"""
    names_data = {
        'surname': None,
        'given_names': None
    }

    # Mots-clés pour identifier les noms
    surname_keywords = [
        'SURNAME', 'NOM', 'APELLIDO', 'COGNOME', 'NACHNAME', 'NAZWISKO',
        'FAMILY NAME', 'LAST NAME', 'اللقب', 'ФАМИЛИЯ'
    ]

    given_name_keywords = [
        'GIVEN NAMES', 'PRENOM', 'NOMBRE', 'NOME', 'VORNAME', 'IMIE',
        'FIRST NAME', 'الاسم', 'ИМЯ', 'PRENOMS'
    ]

    for block in text_blocks:
        block_upper = block.upper()

        # Recherche du nom de famille (arrêt au premier trouvé)
        if names_data['surname'] is None:
            for keyword in surname_keywords:
                if keyword in block_upper:
                    parts = block.split()
                    for i, part in enumerate(parts):
                        if keyword in part.upper() and i + 1 < len(parts):
                            names_data['surname'] = parts[i + 1].upper()
                            break
                    if names_data['surname']:
                        break

        # Recherche du prénom (arrêt au premier trouvé)
        if names_data['given_names'] is None:
            for keyword in given_name_keywords:
                if keyword in block_upper:
                    parts = block.split()
                    for i, part in enumerate(parts):
                        if keyword in part.upper() and i + 1 < len(parts):
                            names_data['given_names'] = ' '.join(parts[i + 1:]).title()
                            break
                    if names_data['given_names']:
                        break

    return names_data

def extract_nationality(text_blocks):
    """Extrait la nationalité"""
    text_combined = ' '.join([block for block in text_blocks]).upper()

    # Nationalités courantes (format adjectif)
    nationalities = {
        'FRENCH': ['FRANÇAISE', 'FRANCAISE', 'FRENCH'],
        'GERMAN': ['DEUTSCHE', 'GERMAN'],
        'SPANISH': ['ESPAÑOLA', 'ESPANOLA', 'SPANISH'],
        'ITALIAN': ['ITALIANA', 'ITALIAN'],
        'BRITISH': ['BRITISH', 'BRITANNIQUE'],
        'AMERICAN': ['AMERICAN', 'AMERICAINE'],
        'CANADIAN': ['CANADIAN', 'CANADIENNE'],
        'MOROCCAN': ['MAROCAINE', 'MOROCCAN', 'مغربية'],
        'ALGERIAN': ['ALGERIENNE', 'ALGERIAN', 'جزائرية'],
        'TUNISIAN': ['TUNISIENNE', 'TUNISIAN', 'تونسية'],
        'SENEGALESE': ['SENEGALAISE', 'SENEGALESE'],
        'MALIAN': ['MALIENNE', 'MALIAN']
    }

    for nationality, variants in nationalities.items():
        if any(variant in text_combined for variant in variants):
            return nationality

    return None

def extract_sex(text_blocks):
    """Extrait le sexe/genre"""
    text_combined = ' '.join([block for block in text_blocks]).upper()

    # Patterns pour détecter le sexe
    if any(pattern in text_combined for pattern in ['SEX M', 'SEXE M', 'MALE', 'MASCULIN', 'M/']):
        return 'M'
    elif any(pattern in text_combined for pattern in ['SEX F', 'SEXE F', 'FEMALE', 'FEMININ', 'F/']):
        return 'F'

    return None

def extract_document_data(image_path):
    """Fonction principale d'extraction internationale"""
    try:
        # Initialiser EasyOCR avec langues essentielles (optimisé)
        reader = easyocr.Reader(['en', 'fr', 'es'], gpu=False)

        # Extraire le texte avec confidence élevée
        results = reader.readtext(image_path)
        text_blocks = [result[1] for result in results if result[2] > 0.4]  # Confiance > 40%

        if not text_blocks:
            return {
                'status': 'error',
                'error': 'No text detected in document'
            }

        # Analyses génériques
        doc_type = detect_document_type(text_blocks)
        country = detect_country(text_blocks)
        names = extract_names(text_blocks)

        text_combined = ' '.join(text_blocks)
        dates = extract_dates(text_combined)
        numbers = extract_document_numbers(text_combined)
        nationality = extract_nationality(text_blocks)
        sex = extract_sex(text_blocks)

        # Structure de données générique (sans redondance)
        extracted_data = {
            'surname': names['surname'],
            'givenNames': names['given_names'],
            'nationality': nationality,
            'sex': sex,
            'documentNumbers': numbers[:3] if numbers else [],  # Maximum 3 numéros
            'dates': dates[:3] if dates else []  # Maximum 3 dates
        }

        # Nettoyage des valeurs nulles
        extracted_data = {k: v for k, v in extracted_data.items() if v is not None and v != []}

        return {
            'status': 'success',
            'documentType': doc_type,
            'issuingCountry': country,
            'data': extracted_data,
            'confidence': 'medium',
            'rawText': text_blocks  # Pour debug
        }

    except Exception as e:
        return {
            'status': 'error',
            'error': str(e)
        }

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(json.dumps({'error': 'Usage: python document_extractor.py <image_path>'}))
        sys.exit(1)

    image_path = sys.argv[1]
    result = extract_document_data(image_path)
    print(json.dumps(result, ensure_ascii=False, indent=2))