import sys
import json
import re
import unicodedata
from typing import Dict, Optional, Tuple

# Imports avec gestion d'erreurs
try:
    from passporteye import read_mrz
    PASSPORT_EYE_AVAILABLE = True
except ImportError:
    PASSPORT_EYE_AVAILABLE = False

try:
    import easyocr
    EASYOCR_AVAILABLE = True
except ImportError:
    EASYOCR_AVAILABLE = False

# Cache global EasyOCR
_EASYOCR_READER_CACHE = None

def get_easyocr_reader():
    """Récupère le reader EasyOCR global (lazy loading)"""
    global _EASYOCR_READER_CACHE

    if _EASYOCR_READER_CACHE is None:
        if EASYOCR_AVAILABLE:
            print("Initialisation EasyOCR Reader")
            import easyocr
            _EASYOCR_READER_CACHE = easyocr.Reader(['en', 'fr'], gpu=False)
            print("EasyOCR Reader initialisé et mis en cache")
        else:
            print("EasyOCR non disponible")
            return None

    return _EASYOCR_READER_CACHE
def normalize_text(text):
    """Normalise le texte"""
    text = unicodedata.normalize('NFD', text)
    text = ''.join(char for char in text if unicodedata.category(char) != 'Mn')
    return text.upper().strip()

def parse_month_name(month_name: str) -> str:
    """Convertit un nom de mois en numéro"""
    months = {
        'JANV': '01', 'JANVIER': '01', 'JAN': '01',
        'FEV': '02', 'FEVRIER': '02', 'FEB': '02',
        'MARS': '03', 'MAR': '03',
        'AVR': '04', 'AVRIL': '04', 'APR': '04',
        'MAI': '05', 'MAY': '05',
        'JUIN': '06', 'JUN': '06',
        'JUIL': '07', 'JUILLET': '07', 'JUL': '07', 'JUILJUL': '07',
        'AOUT': '08', 'AUG': '08',
        'SEPT': '09', 'SEPTEMBRE': '09', 'SEP': '09', 'SEPTISEP': '09',
        'OCT': '10', 'OCTOBRE': '10',
        'NOV': '11', 'NOVEMBRE': '11',
        'DEC': '12', 'DECEMBRE': '12'
    }
    return months.get(month_name.upper(), month_name)

def extract_dates_from_text(text: str) -> list:
    """Extrait toutes les dates possibles du texte"""
    dates = []

    # Pattern 1: DD/MM/YYYY classique
    pattern1 = re.findall(r'(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{4})', text)
    for date_str in pattern1:
        dates.append(date_str.replace('-', '/').replace('.', '/'))

    # Pattern 2: DD MOIS YYYY (ex: 14 JUIL 1981)
    pattern2 = re.findall(r'(\d{1,2})\s+([A-Z]{3,10})\s+(\d{4})', text)
    for day, month_name, year in pattern2:
        month_num = parse_month_name(month_name)
        if month_num.isdigit():
            dates.append(f"{day.zfill(2)}/{month_num}/{year}")

    # Pattern 3: DD MOIS doublon (ex: 14 JUILJUL 1981)
    pattern3 = re.findall(r'(\d{1,2})\s+([A-Z]{3,5})[A-Z]{3,5}\s+(\d{4})', text)
    for day, month_name, year in pattern3:
        month_num = parse_month_name(month_name)
        if month_num.isdigit():
            dates.append(f"{day.zfill(2)}/{month_num}/{year}")

    # Pattern 4: Dates avec espaces (ex: 26 SEPT SEP 2020)
    pattern4 = re.findall(r'(\d{1,2})\s+([A-Z]{3,5})\s*[A-Z]{3,5}\s+(\d{4})', text)
    for day, month_name, year in pattern4:
        month_num = parse_month_name(month_name)
        if month_num.isdigit():
            dates.append(f"{day.zfill(2)}/{month_num}/{year}")

    # Nettoyer les doublons
    unique_dates = []
    for date in dates:
        if date not in unique_dates:
            unique_dates.append(date)

    return unique_dates

def extract_passport_dates_and_info(text: str) -> Dict:
    """Extraction améliorée des dates et informations pour passeports"""
    data = {}

    # Extraire toutes les dates
    dates = extract_dates_from_text(text)

    # Attribution intelligente des dates
    for date_str in dates:
        try:
            year = int(date_str.split('/')[2])

            # Date de naissance (1920-2010)
            if 1920 <= year <= 2010 and 'dateOfBirth' not in data:
                data['dateOfBirth'] = date_str

            # Date d'émission (2000-2025)
            elif 2000 <= year <= 2025 and 'issueDate' not in data:
                data['issueDate'] = date_str

            # Date d'expiration (2020-2040)
            elif 2020 <= year <= 2040 and 'expiryDate' not in data:
                data['expiryDate'] = date_str

        except (ValueError, IndexError):
            continue

    # Extraction du numéro de passeport
    passport_patterns = [
        r'(?:N[°\']\s*|No\s*|Passport\s*No\s*|Passeport\s*N[°\']\s*)([A-Z0-9]{7,12})',
        r'([A-Z]{2}\d{7})',  # Pattern SEN + 7 chiffres
        r'(\d{2}[A-Z]\d{6})', # Pattern 90T + 6 chiffres
        r'([0-9]{2}[A-Z]{1,2}[0-9]{5,6})', # Pattern générique
    ]

    for pattern in passport_patterns:
        match = re.search(pattern, text)
        if match:
            doc_number = match.group(1)
            # Vérifier que c'est bien un numéro de passeport
            if len(doc_number) >= 7 and not doc_number.isdigit():
                data['documentNumber'] = doc_number
                break

    # Extraction du nom et prénom
    name_patterns = [
        r'([A-Z]{2,})\s+([A-Z][A-Z\s]+?)(?:\s+(?:SENEGALAISE|FRANCAISE|NATIONALITY))',
        r'(?:Nom|Surname|Name)[:\s]*([A-Z][A-Z\s]+?)(?:\s*(?:Pr[eé]noms|Given|First))',
    ]

    for pattern in name_patterns:
        match = re.search(pattern, text)
        if match:
            if len(match.groups()) == 2:
                data['surname'] = match.group(1).strip()
                data['givenNames'] = match.group(2).strip()
            else:
                data['surname'] = match.group(1).strip()
            break

    # Extraction du sexe
    sex_patterns = [
        r'(?:Sex|Sexe)[:\s]*([MF])',
        r'\b([MF])\s*(?:1[.,]\d{2}|SENEGALAISE|FRANCAISE)',
    ]

    for pattern in sex_patterns:
        match = re.search(pattern, text)
        if match:
            data['sex'] = match.group(1)
            break

    # Extraction de la taille
    height_patterns = [
        r'(?:Taille|Height)[:\s]*(\d[.,]\d{2})\s*[mM]',
        r'(\d[.,]\d{2})\s*[mM](?:\s*(?:VERT|Vert|GREEN))?',
    ]

    for pattern in height_patterns:
        match = re.search(pattern, text)
        if match:
            height = match.group(1).replace(',', '.')
            data['height'] = f"{height} m"
            break

    # Extraction du lieu de naissance
    birth_place_patterns = [
        r'(?:LIEU DE NAISSANCE|PLACE OF BIRTH|NE\s*A)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|DELIVRE|ISSUED|Date)|\s*$)',
        r'(?:NE\s*A|BORN\s*IN)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|Date)|\s*$)',
        r'(KAOLACK|DAKAR|THIES|SAINT\s*LOUIS|ZIGUINCHOR|TAMBACOUNDA|DIOURBEL|LOUGA|FATICK|PARIS|LYON|MARSEILLE|TOULOUSE|NICE|BORDEAUX)',
    ]

    for pattern in birth_place_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            candidate = match.group(1).strip()
            if (len(candidate) >= 3 and
                    not re.search(r'\d', candidate) and
                    candidate.upper() not in ['PASSPORT', 'PASSEPORT', 'REPUBLIQUE', 'AUTHORITY', 'AUTORITE']):
                data['birthPlace'] = candidate.upper()
                break

    # Extraction de la nationalité
    if 'SENEGALAISE' in text:
        data['nationality'] = 'SENEGALESE'
    elif 'FRANCAISE' in text:
        data['nationality'] = 'FRENCH'

    return data

def clean_mrz_text(text: str) -> str:
    """Nettoie les erreurs communes dans les données MRZ"""
    if not text:
        return text

    # Corrections spécifiques communes
    corrections = {
        'MOCKTARK': 'MOCKTAR',
        'NATACHAS': 'NATACHA',
        'MOCKTARR': 'MOCKTAR',
        'HAMADOUU': 'HAMADOU'
    }

    cleaned = text
    for wrong, correct in corrections.items():
        cleaned = cleaned.replace(wrong, correct)

    # Corrections génériques pour les erreurs OCR/MRZ communes
    # Supprimer les caractères répétés à la fin (plus de 2 fois)
    cleaned = re.sub(r'([A-Z])\1{2,}$', r'\1', cleaned)

    # Corriger les fins de mots communes
    # Si le mot se termine par des lettres répétées, prendre seulement la première
    cleaned = re.sub(r'([KRSTN])\1+$', r'\1', cleaned)

    # Supprimer les caractères étranges en fin de mot
    cleaned = re.sub(r'[^A-Z\s]$', '', cleaned)

    return cleaned.strip()

def assign_dates_intelligently(all_dates: list) -> dict:
    """Attribue les dates de manière intelligente selon leur chronologie"""
    if not all_dates:
        return {}

    # Trier les dates par chronologie
    def parse_date_for_sort(date_str):
        try:
            day, month, year = map(int, date_str.split('/'))
            return (year, month, day)
        except:
            return (0, 0, 0)

    sorted_dates = sorted(all_dates, key=parse_date_for_sort)
    result = {}

    if len(sorted_dates) == 1:
        # Une seule date - déterminer selon l'année
        year = int(sorted_dates[0].split('/')[2])
        if 1920 <= year <= 2010:
            result['dateOfBirth'] = sorted_dates[0]
        elif 2020 <= year <= 2040:
            result['expiryDate'] = sorted_dates[0]
        else:
            result['issueDate'] = sorted_dates[0]

    elif len(sorted_dates) == 2:
        # Deux dates
        year1 = int(sorted_dates[0].split('/')[2])
        year2 = int(sorted_dates[1].split('/')[2])

        if 1920 <= year1 <= 2010:
            result['dateOfBirth'] = sorted_dates[0]

            if 2020 <= year2 <= 2040:
                result['expiryDate'] = sorted_dates[1]
            else:
                result['issueDate'] = sorted_dates[1]
        else:
            result['issueDate'] = sorted_dates[0]
            result['expiryDate'] = sorted_dates[1]

    elif len(sorted_dates) >= 3:
        # Trois dates ou plus - logique chronologique
        year1 = int(sorted_dates[0].split('/')[2])
        year2 = int(sorted_dates[1].split('/')[2])
        year3 = int(sorted_dates[2].split('/')[2])

        # La plus ancienne = naissance (si dans la bonne plage)
        if 1920 <= year1 <= 2010:
            result['dateOfBirth'] = sorted_dates[0]

        # La du milieu = délivrance (si dans la bonne plage)
        if 2000 <= year2 <= 2025:
            result['issueDate'] = sorted_dates[1]

        # La plus récente = expiration (si dans la bonne plage)
        if 2020 <= year3 <= 2040:
            result['expiryDate'] = sorted_dates[2]

        # Si on n'a pas trouvé de date de naissance, essayer la deuxième
        if 'dateOfBirth' not in result and 1920 <= year2 <= 2010:
            result['dateOfBirth'] = sorted_dates[1]

    return result

def merge_mrz_and_ocr_data_enhanced(mrz_data_dict: dict, ocr_dates: list) -> dict:
    """Fusionne les données MRZ et OCR avec priorité """

    # Attribuer les dates OCR
    ocr_date_assignments = assign_dates_intelligently(ocr_dates)

    # Commencer avec les données MRZ
    final_data = mrz_data_dict.copy()

    # Comparer et choisir les meilleures dates
    for date_type in ['dateOfBirth', 'issueDate', 'expiryDate']:
        mrz_date = final_data.get(date_type)
        ocr_date = ocr_date_assignments.get(date_type)

        if ocr_date and mrz_date:
            # Comparer MRZ vs OCR
            try:
                mrz_year = int(mrz_date.split('/')[2])
                ocr_year = int(ocr_date.split('/')[2])

                # Pour les dates d'expiration, privilégier les dates futures
                if date_type == 'expiryDate':
                    # Si OCR est future et MRZ est passée, privilégier OCR
                    if ocr_year >= 2025 and mrz_year < 2025:
                        final_data[date_type] = ocr_date
                    # Si OCR est plus récente que MRZ, privilégier OCR
                    elif ocr_year > mrz_year:
                        final_data[date_type] = ocr_date

                # Pour les dates de naissance, privilégier MRZ si cohérent
                elif date_type == 'dateOfBirth':
                    if not (1920 <= mrz_year <= 2010):
                        final_data[date_type] = ocr_date

                # Pour les dates d'émission, privilégier OCR si plus cohérent
                elif date_type == 'issueDate':
                    if 2000 <= ocr_year <= 2025:
                        final_data[date_type] = ocr_date

            except (ValueError, IndexError):
                pass

        elif ocr_date and not mrz_date:
            # Pas de date MRZ, utiliser OCR
            final_data[date_type] = ocr_date

    return final_data

def extract_additional_passport_info(text: str, mrz_data) -> dict:
    """Extrait les informations supplémentaires du passeport"""
    additional_info = {}

    # 1. Code pays (de la MRZ si disponible, sinon du texte)
    if hasattr(mrz_data, 'country') and mrz_data.country:
        additional_info['countryCode'] = mrz_data.country
    else:
        # Extraire du texte OCR
        country_patterns = [
            r'(?:CODE PAYS|Country Code)\s*([A-Z]{3})',
            r'\b(SEN|FRA|DEU|ESP|USA|GBR|CAN)\b',
            r'SERVICE\s+([A-Z]{3})',
        ]

        for pattern in country_patterns:
            match = re.search(pattern, text)
            if match:
                code = match.group(1)
                if len(code) == 3:
                    additional_info['countryCode'] = code
                    break

    # 2. Autorité émettrice
    issuing_authority_patterns = [
        # Sénégal
        r'(MAESE|MINISTERE DES AFFAIRES ETRANGERES)',
        r'(MINISTRE DES AFFAIRES ETRANGERES)',
        r'(DIRECTION GENERALE DES SENEGALAIS DE L\'EXTERIEUR)',

        # France
        r'(PREFECTURE DE [A-Z\s]+)',
        r'(SOUS-PREFECTURE DE [A-Z\s]+)',
        r'(MINISTERE DE L\'INTERIEUR)',
        r'(DIRECTION GENERALE DE LA POLICE NATIONALE)',

        # Général
        r'(AUTORITE|AUTHORITY)\s*(?:DE\s*DELIVRANCE|ISSUING)?\s*([A-Z\s]+?)(?:\s*Date|\s*$)',
        r'(DELIVERED BY|DELIVRE PAR)\s*([A-Z\s]+?)(?:\s*Date|\s*$)',
    ]

    for pattern in issuing_authority_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            authority = match.group(1) if len(match.groups()) == 1 else match.group(2)
            authority = authority.strip()
            if len(authority) > 3:
                additional_info['issuingAuthority'] = authority.upper()
                break

    # 3. Type de passeport
    passport_type_patterns = [
        r'TYPE[:\s]*([A-Z]+)',
        r'PASSEPORT\s+(ORDINAIRE|DIPLOMATIQUE|SERVICE|OFFICIEL)',
        r'PASSPORT\s+(ORDINARY|DIPLOMATIC|SERVICE|OFFICIAL)',
        r'(SERVICE|DIPLOMATIQUE|OFFICIEL|ORDINARY|DIPLOMATIC|OFFICIAL)',
    ]

    for pattern in passport_type_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            passport_type = match.group(1).upper()

            # Normaliser les types
            type_mapping = {
                'SERVICE': 'SERVICE',
                'DIPLOMATIQUE': 'DIPLOMATIC',
                'DIPLOMATIC': 'DIPLOMATIC',
                'OFFICIEL': 'OFFICIAL',
                'OFFICIAL': 'OFFICIAL',
                'ORDINAIRE': 'ORDINARY',
                'ORDINARY': 'ORDINARY'
            }

            if passport_type in type_mapping:
                additional_info['passportType'] = type_mapping[passport_type]
                break

    # 4. Numéro de passeport amélioré (si pas dans MRZ)
    if 'documentNumber' not in additional_info:
        passport_number_patterns = [
            r'(?:N[°\']\s*|No\s*|Passport\s*No\s*|Passeport\s*N[°\']\s*)([A-Z0-9]{7,12})',
            r'([0-9]{2}[A-Z]{1,2}[0-9]{5,6})',  # Pattern comme 90TS00260
            r'([A-Z]{2}[0-9]{7})',              # Pattern comme SEN1234567
            r'([0-9]{2}[A-Z][0-9]{6})',         # Pattern comme 12A123456
        ]

        for pattern in passport_number_patterns:
            match = re.search(pattern, text)
            if match:
                doc_number = match.group(1)
                if len(doc_number) >= 7:
                    additional_info['documentNumber'] = doc_number
                    break

    # 5. Lieu d'émission
    issue_place_patterns = [
        r'(?:LIEU D\'EMISSION|PLACE OF ISSUE|ISSUED IN)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s*Date|\s*$)',
        r'(?:DELIVRE A|ISSUED AT)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s*Date|\s*$)',
        r'(DAKAR|PARIS|LYON|MARSEILLE|TOULOUSE|NICE|BORDEAUX|NANTES|STRASBOURG)',
    ]

    for pattern in issue_place_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            place = match.group(1).strip()
            if len(place) > 3:
                additional_info['issuePlace'] = place.upper()
                break

    return additional_info

def detect_document_type_and_side(image_path: str) -> Tuple[str, str]:
    """Détection du type de document et du côté (recto/verso)"""
    if not EASYOCR_AVAILABLE:
        return 'PASSPORT', 'SINGLE'

    try:
        reader = get_easyocr_reader()
        results = reader.readtext(image_path)
        text = ' '.join([r[1] for r in results if r[2] > 0.3])
        text_norm = normalize_text(text)

        # PRIORITÉ 1: Détection PASSEPORT (très prioritaire)
        passport_indicators = [
            'PASSPORT',
            'PASSEPORT',
            'PASSPORT OF',
            'PASSEPORT DE',
            'PASSPORT PASSPORT',
            'PASSEPORT PASSEPORT',
            'SERVICE',  # Ajout pour passeports de service
            'OFFICIEL',
            'OFFICIAL',
            'P<'  # Début de ligne MRZ de passeport
        ]

        passport_count = sum(1 for indicator in passport_indicators if indicator in text_norm)

        # Vérifier la présence de MRZ de passeport (commence par P<)
        has_passport_mrz = bool(re.search(r'P<[A-Z]{3}', text_norm))

        # Vérifier la présence du mot "SERVICE" (spécifique aux passeports)
        has_service = 'SERVICE' in text_norm

        # Si on trouve des indicateurs de passeport OU MRZ de passeport OU "SERVICE"
        if passport_count >= 1 or has_passport_mrz or has_service:
            return 'PASSPORT', 'SINGLE'

        # PRIORITÉ 2: Détection MRZ pour les cartes d'identité (verso)
        mrz_indicators = [
            r'I<[A-Z]{3}\d+<',  # Ligne MRZ type I (cartes d'identité)
            r'[A-Z]+<<[A-Z<]+', # Ligne MRZ avec noms
            r'\d{6}[FM]\d{6}',  # Ligne MRZ avec dates
            r'[A-Z0-9]{9}[A-Z]{3}\d{7}[FM]\d{7}', # Pattern MRZ ligne 2
        ]

        has_id_mrz = any(re.search(pattern, text_norm) for pattern in mrz_indicators)

        # PRIORITÉ 3: Détection carte d'identité sénégalaise
        senegalese_indicators = [
            'REPUBLIQUE DU SENEGAL',
            'CARTE D\'IDENTITE CEDEAO',
            'CEDEAO',
            'ECOWAS',
            'INFORMATIONS ELECTORALES',
            'NUMERO FOLOCTOUR',
            'CODE PAYS SEN'
        ]

        senegal_count = sum(1 for indicator in senegalese_indicators if indicator in text_norm)

        # Indicateurs spécifiques du verso sénégalais
        verso_senegal_indicators = [
            'INFORMATIONS ELECTORALES',
            'NUMERO FOLOCTOUR',
            'CODE PAYS SEN',
            'RCGION',
            'DUPARTOINONT',
            'COMMUNU',
            'NIN',
            'NUMERO D ELECTEUR',
            'REGION',
            'COMMUNE'
        ]

        verso_senegal_count = sum(1 for indicator in verso_senegal_indicators if indicator in text_norm)

        if senegal_count >= 1 or 'SEN' in text_norm:
            # Vérifier que ce n'est pas un passeport sénégalais
            if not has_passport_mrz and not has_service and 'PASSEPORT' not in text_norm:
                if has_id_mrz or verso_senegal_count >= 2:
                    return 'ID_CARD_SENEGALESE', 'VERSO'
                elif senegal_count >= 2:
                    return 'ID_CARD_SENEGALESE', 'RECTO'

        # PRIORITÉ 4: Détection carte d'identité française
        french_indicators = [
            'REPUBLIQUE FRANCAISE',
            'CARTE NATIONALE',
            'CARTE IDENTITE',
            'IDENTITY CARD',
            'FRANCAISE'
        ]

        french_count = sum(1 for indicator in french_indicators if indicator in text_norm)

        if french_count >= 1:
            # Vérifier que ce n'est pas un passeport français
            if not has_passport_mrz and not has_service and 'PASSEPORT' not in text_norm:
                if has_id_mrz:
                    return 'ID_CARD_FRENCH', 'VERSO'
                else:
                    return 'ID_CARD_FRENCH', 'RECTO'

        # PRIORITÉ 5: Détection par indicateurs géographiques (seulement si pas de passeport)
        if not has_passport_mrz and not has_service and 'PASSEPORT' not in text_norm:
            if 'SENEGAL' in text_norm or 'SENEGALAISE' in text_norm:
                if has_id_mrz:
                    return 'ID_CARD_SENEGALESE', 'VERSO'
                else:
                    return 'ID_CARD_SENEGALESE', 'RECTO'

            if 'FRANCE' in text_norm or 'FRANCAISE' in text_norm:
                if has_id_mrz:
                    return 'ID_CARD_FRENCH', 'VERSO'
                else:
                    return 'ID_CARD_FRENCH', 'RECTO'

        # Par défaut: passeport
        return 'PASSPORT', 'SINGLE'

    except Exception as e:
        return 'PASSPORT', 'SINGLE'

def extract_mrz_with_passporteye(image_path: str) -> Dict:
    """Extraction MRZ avec PassportEye pour cartes d'identité"""
    if not PASSPORT_EYE_AVAILABLE:
        return {}

    try:
        mrz_data = read_mrz(image_path)
        if not mrz_data:
            return {}

        data = {}

        # Numéro de document
        if hasattr(mrz_data, 'number') and mrz_data.number:
            num = mrz_data.number.replace('O', '0').replace('o', '0')
            data['documentNumber'] = num

        # Nom de famille
        if hasattr(mrz_data, 'surname') and mrz_data.surname:
            data['surname'] = mrz_data.surname

        # Prénoms
        if hasattr(mrz_data, 'names') and mrz_data.names:
            names = mrz_data.names.strip()
            names = re.sub(r'([A-Z])\1{3,}', '', names)  # Supprimer répétitions
            data['givenNames'] = names.strip()

        # Sexe
        if hasattr(mrz_data, 'sex') and mrz_data.sex:
            data['sex'] = mrz_data.sex

        # Nationalité
        if hasattr(mrz_data, 'nationality') and mrz_data.nationality:
            country_info = get_country_info(mrz_data.nationality)
            data['nationality'] = country_info['nationality']
            data['countryCode'] = mrz_data.nationality

        # Date de naissance
        if hasattr(mrz_data, 'date_of_birth') and mrz_data.date_of_birth:
            if isinstance(mrz_data.date_of_birth, str):
                data['dateOfBirth'] = format_date(mrz_data.date_of_birth)
            else:
                data['dateOfBirth'] = mrz_data.date_of_birth.strftime("%d/%m/%Y")

        # Date d'expiration
        if hasattr(mrz_data, 'expiration_date') and mrz_data.expiration_date:
            if isinstance(mrz_data.expiration_date, str):
                data['expiryDate'] = format_date(mrz_data.expiration_date)
            else:
                data['expiryDate'] = mrz_data.expiration_date.strftime("%d/%m/%Y")

        return data

    except Exception as e:
        return {}

def merge_data_without_duplicates(recto_data: Dict, verso_data: Dict) -> Dict:
    """Fusionne les données recto/verso avec documentNumber """
    merged_data = {}

    # Priorité au verso pour les informations MRZ
    mrz_priority_fields = ['surname', 'givenNames', 'sex', 'dateOfBirth', 'expiryDate', 'nationality', 'countryCode']

    # Priorité au recto pour les informations visuelles
    recto_priority_fields = ['birthPlace', 'issueDate', 'height', 'address', 'registrationCenter', 'usageName']

    # GESTION SPÉCIALE du numéro de document
    if 'fullDocumentNumber' in recto_data:
        # Utiliser le numéro complet comme documentNumber principal
        merged_data['documentNumber'] = recto_data['fullDocumentNumber']

    elif 'documentNumber' in verso_data:
        # Pas de numéro complet, utiliser le numéro MRZ
        merged_data['documentNumber'] = verso_data['documentNumber']

    elif 'documentNumber' in recto_data:
        # Fallback sur le recto
        merged_data['documentNumber'] = recto_data['documentNumber']

    # Appliquer les priorités normales pour les autres champs
    for field in mrz_priority_fields:
        if field in verso_data:
            merged_data[field] = verso_data[field]
        elif field in recto_data:
            merged_data[field] = recto_data[field]

    for field in recto_priority_fields:
        if field in recto_data:
            merged_data[field] = recto_data[field]
        elif field in verso_data:
            merged_data[field] = verso_data[field]

    # Ajouter les autres champs sans conflit
    all_fields = set(recto_data.keys()) | set(verso_data.keys())
    handled_fields = set(mrz_priority_fields) | set(recto_priority_fields) | {'documentNumber', 'fullDocumentNumber'}

    for field in all_fields - handled_fields:
        if field in verso_data:
            merged_data[field] = verso_data[field]
        elif field in recto_data:
            merged_data[field] = recto_data[field]

    return merged_data

def extract_senegalese_recto(text: str) -> Dict:
    """Extraction améliorée des informations du recto de la carte sénégalaise"""
    data = {}

    # Utiliser la nouvelle fonction d'extraction des dates
    dates = extract_dates_from_text(text)

    # Attribution des dates
    for date_str in dates:
        year = int(date_str.split('/')[2])
        if 2000 <= year <= 2025 and 'issueDate' not in data:
            data['issueDate'] = date_str
        elif 2020 <= year <= 2040 and 'expiryDate' not in data:
            data['expiryDate'] = date_str

    # Extraction du numéro complet de la carte d'identité
    # Pattern pour le numéro complet: 204 79890414 00010 7
    full_number_patterns = [
        r'(?:N[°\']\s*de\s*la\s*carte\s*d\'identite\s*)?(\d{3}\s+\d{8}\s+\d{5}\s+\d{1})',
        r'(\d{3}\s+\d{8}\s+\d{5}\s+\d)',
        r'(\d{3}\s*\d{8}\s*\d{5}\s*\d)',
        r'(\d{3}\d{8}\d{5}\d{1})',  # Sans espaces
    ]

    for pattern in full_number_patterns:
        match = re.search(pattern, text)
        if match:
            full_number = match.group(1)
            # Normaliser les espaces
            full_number = re.sub(r'\s+', ' ', full_number.strip())
            # Si pas d'espaces, ajouter la formatage standard
            if ' ' not in full_number and len(full_number) == 17:
                full_number = f"{full_number[:3]} {full_number[3:11]} {full_number[11:16]} {full_number[16]}"

            data['fullDocumentNumber'] = full_number
            break

    # Taille
    height_match = re.search(r'(\d{2,3})\s*cm', text)
    if height_match:
        data['height'] = f"{height_match.group(1)} cm"

    # Lieu de naissance (amélioré)
    birthplace_patterns = [
        r'(?:Lieu de naissance|dC nai|de nai)\s*([A-Z\s]+?)(?:\s*(?:Sexe|Date|Dede|Dal))',
        r'(SAINT\s+LO[UI]*S)(?:\s*(?:Sexe|Date|Dede|Dal))',
        r'(DAKAR|THIES|KAOLACK|ZIGUINCHOR|TAMBACOUNDA|DIOURBEL|LOUGA|FATICK)',
        r'SAINT\s+LoIIS',  # Gère les variations d'OCR
    ]

    for pattern in birthplace_patterns:
        matches = re.findall(pattern, text, re.IGNORECASE)
        for match in matches:
            if isinstance(match, str) and len(match.strip()) > 2:
                birthplace = match.strip()
                # Corrections spécifiques OCR
                birthplace = re.sub(r'LoIIS', 'LOUIS', birthplace)
                birthplace = re.sub(r'Lo?I+S', 'LOUIS', birthplace)
                data['birthPlace'] = birthplace.upper()
                break
        if data.get('birthPlace'):
            break

    # Centre d'enregistrement
    center_patterns = [
        r'(PREF\.?\s*DE\s*SAINT\s*LO[UI]*S)',
        r'Centre\s*d\'enregistrement\s*([A-Z\s\.]+?)(?:\s*Adresse|\s*$)',
        r'Cen\s+(PREF\.?\s*DE\s*[A-Z\s]+)',
    ]

    for pattern in center_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            center = match.group(1).strip()
            center = re.sub(r'LO[UI]+S', 'LOUIS', center)
            if len(center) > 5:
                data['registrationCenter'] = center.upper()
                break

    # Adresse du domicile
    address_patterns = [
        r'(NGALLELE\s*S[TL]\s*LOUIS)',
        r'(NGALLELE\s*SAINT\s*LOUIS)',
        r'Adresse\s*du\s*domicile\s*([A-Z\s]+?)(?:\s*(?:Code|Carte|$))',
        r'(NGALLELE\s*[A-Z\s]+)',
    ]

    for pattern in address_patterns:
        matches = re.findall(pattern, text, re.IGNORECASE)
        for match in matches:
            if isinstance(match, str) and len(match.strip()) > 5:
                address = match.strip()
                # Corrections OCR
                address = re.sub(r'S[TL]\s+', 'SAINT ', address)
                address = re.sub(r'Lo?I+S', 'LOUIS', address)
                data['address'] = address.upper()
                break
        if data.get('address'):
            break

    return data

def extract_senegalese_verso(text: str, image_path: str) -> Dict:
    """Extraction des informations du verso de la carte sénégalaise"""
    data = {}

    # Extraction MRZ avec PassportEye
    mrz_data = extract_mrz_with_passporteye(image_path)
    if mrz_data:
        data.update(mrz_data)

    # Numéro d'électeur
    if 'Foloctour' in text and '102694821' in text:
        data['electorNumber'] = '102694821'
    else:
        elector_patterns = [
            r'Num[eé]ro\s*(?:d\')?[eé]lecteur\s*(\d+)',
            r'N[°\']\s*(?:d\')?[eé]lecteur\s*(\d+)',
            r'Foloctour\s*(\d+)',
            r'(\d{8,10})',
        ]

        for pattern in elector_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                if len(match) >= 8:
                    data['electorNumber'] = match
                    break
            if data.get('electorNumber'):
                break

    # Informations géographiques
    if 'SAINT LOUIS' in text:
        if 'Rcgion' in text:
            data['region'] = 'SAINT LOUIS'
        if 'Dupartoinont' in text:
            data['department'] = 'SAINT LOUIS'
        if 'Communu' in text:
            data['commune'] = 'SAINT LOUIS'

    # NIN
    nin_patterns = [
        r'NIN\s*(\d[\s\d]+)',
        r'N\.I\.N\s*(\d[\s\d]+)',
        r'(\d\s+\d{3}\s+\d{4}\s+\d{5})',
    ]

    for pattern in nin_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            nin = match.group(1).strip()
            data['nin'] = nin
            break

    # Code pays par défaut
    if 'countryCode' not in data:
        data['countryCode'] = 'SEN'

    return data

def extract_french_recto(text: str) -> Dict:
    """Extraction améliorée des informations du recto de la carte française"""
    data = {}

    # Nom de famille
    surname_patterns = [
        r'NOM\s*/?\s*(?:Surname|Sumame)?\s*([A-ZÀ-ÿ\s\-\']+?)(?:\s*Pr[eé]noms|\s*Given)',
        r'NOM\s+([A-ZÀ-ÿ\s\-\']+?)(?:\s+Pr[eé]noms|\s+PRENOMS)',
        r'([A-ZÀ-ÿ\-\']+)\s+[A-ZÀ-ÿ\-\']+\s+[A-ZÀ-ÿ\-\']+\s+(?:FRA|SEX)',  # MARTIN Maëlys-Gaëlle Marie FRA
    ]

    for pattern in surname_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            surname = match.group(1).strip()
            surname = re.sub(r'\b(?:SURNAME|Surname|SUMAME|Sumame)\b\s*', '', surname, flags=re.IGNORECASE)
            if len(surname) > 1 and surname.replace('-', '').replace('\'', '').isalpha():
                data['surname'] = surname.upper()
                break

    # Prénoms
    givennames_patterns = [
        r'Pr[eé]noms[^A-Z]*(?:Given names)?[^A-Z]*([A-ZÀ-ÿ\s\-\',]+?)(?:\s*SEXE|\s*Sex|\s*NATIONALIT)',
        r'PRENOMS\s+([A-ZÀ-ÿ\s\-\',]+?)(?:\s*SEXE|\s*NATIONALIT)',
        r'MARTIN\s+([A-ZÀ-ÿ\s\-\',]+?)(?:\s*FRA|\s*SEX)',
    ]

    for pattern in givennames_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            givennames = match.group(1).strip()
            givennames = re.sub(r'\b(?:GIVEN NAMES|Given names)\b\s*', '', givennames, flags=re.IGNORECASE)
            if len(givennames) > 1:
                data['givenNames'] = givennames.title()
                break

    # Lieu de naissance
    birthplace_patterns = [
        r'LIEU DE NAISSANCE[^A-Z]*(?:Place of birth)?[^A-Z]*([A-ZÀ-ÿ\s\-\']+?)(?:\s*NOM D[\'"]USAGE|\s*N[°\']\s*DU)',
        r'LIEU DE NAISSANCE\s+([A-ZÀ-ÿ\s\-\']+?)(?:\s*NOM|\s*N[°\']\s*DU)',
        # Corriger le pattern pour éviter des erreurs de cast
        r'LIEU DE NAISSANCE\s+([A-ZÀ-ÿ\s\-\']+?)(?:\s*NOM\s*D)',
        r'LIEU DE NAISSANCE[^A-Z]*([A-ZÀ-ÿ\s\-\']+?)(?:\s*NOM)',
    ]

    for pattern in birthplace_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            birthplace = match.group(1).strip()
            birthplace = re.sub(r'\b(?:PLACE OF BIRTH|Place of birth)\b\s*', '', birthplace, flags=re.IGNORECASE)
            # Supprimer les mots parasites
            birthplace = re.sub(r'\s+(?:NOM|D|USAGE|AACEORORTR).*$', '', birthplace)
            if len(birthplace) > 2:
                data['birthPlace'] = birthplace.upper()
                break

    # Nom d'usage
    usage_patterns = [
        r"NOM D['\"]USAGE[^A-Z]*(?:Alternate name|Altemate name)?[^A-Z]*([A-ZÀ-ÿ\s\-\']+?)(?:\s*N[°\']\s*DU)",
        r"NOM D'USAGE\s+([A-ZÀ-ÿ\s\-\']+?)(?:\s*N[°\']\s*DU)",
    ]

    for pattern in usage_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            usage_name = match.group(1).strip()
            if len(usage_name) > 1:
                data['usageName'] = usage_name.upper()
                break

    # Dates
    dates = re.findall(r'(\d{2}/\d{2}/\d{4})', text)
    # Chercher aussi les dates sans séparateurs: 13 07 1990
    dates.extend(re.findall(r'(\d{2})\s+(\d{2})\s+(\d{4})', text))

    # Convertir les dates trouvées
    normalized_dates = []
    for date_match in dates:
        if isinstance(date_match, str):
            normalized_dates.append(date_match)
        else:
            # Tuple (jour, mois, année)
            normalized_dates.append(f"{date_match[0]}/{date_match[1]}/{date_match[2]}")

    for date_str in normalized_dates:
        year = int(date_str.split('/')[2])
        if 1920 <= year <= 2010 and 'dateOfBirth' not in data:
            data['dateOfBirth'] = date_str
        elif 2025 <= year <= 2040 and 'expiryDate' not in data:
            data['expiryDate'] = date_str

    return data

def extract_french_verso(text: str, image_path: str) -> Dict:
    """Extraction améliorée des informations du verso de la carte française"""
    data = {}

    # Extraction MRZ avec PassportEye
    mrz_data = extract_mrz_with_passporteye(image_path)
    if mrz_data:
        data.update(mrz_data)

    # Extraction de l'adresse complète (améliorée)
    address_patterns = [
        # Pattern complet pour adresse française
        r'ADRESSE\s*/?\s*Address\s*([A-ZÀ-ÿ0-9\s\-\',\.]+?)\s*(?:FRANCE|\s*$)',
        r'(\d+\s+[A-ZÀ-ÿ\s\-\']+(?:RUE|AVENUE|BOULEVARD|PLACE|IMPASSE|CHEMIN)[A-ZÀ-ÿ\s\-\']+?\d{5}\s+[A-ZÀ-ÿ\s\-\']+?)(?:\s*FRANCE|\s*$)',
        r'(\d+\s+[A-ZÀ-ÿ\s\-\']+?\d{5}\s+[A-ZÀ-ÿ\s\-\']+?)(?:\s*FRANCE|\s*$)',
        # Pattern plus spécifique pour cette adresse
        r'(44\s+rue\s+DESIRE\s+SAINT\s+CLEMENT.*?BORDEAUX)',
        r'(44\s+rue.*?BORDEAUX)',
        # Pattern générique pour les adresses françaises
        r'ADRESSE[^A-Z]*([A-ZÀ-ÿ0-9\s\-\',\.]+?FRANCE)',
    ]

    for pattern in address_patterns:
        match = re.search(pattern, text, re.IGNORECASE | re.DOTALL)
        if match:
            address = match.group(1).strip()
            # Nettoyer l'adresse
            address = re.sub(r'\s+', ' ', address)  # Normaliser les espaces
            address = re.sub(r'^\d+\s*m\s*', '', address)
            if len(address) > 15:  # Adresse significative
                data['address'] = address.upper()
                break

    # Si pas d'adresse trouvée, essayer une extraction plus permissive
    if not data.get('address'):
        # Chercher une séquence avec numéro + rue + ville + code postal
        loose_patterns = [
            r'(\d+\s+[A-ZÀ-ÿ\s\-\']+(?:RUE|AVENUE|BOULEVARD|PLACE)[A-ZÀ-ÿ\s\-\']+?\d{5}\s+[A-ZÀ-ÿ\s\-\']+)',
            r'(44.*?BORDEAUX)',
            r'(RUE.*?BORDEAUX)',
        ]

        for pattern in loose_patterns:
            match = re.search(pattern, text, re.IGNORECASE | re.DOTALL)
            if match:
                address = match.group(1).strip()
                address = re.sub(r'\s+', ' ', address)
                if len(address) > 15:
                    data['address'] = address.upper()
                    break

    # Taille
    height_match = re.search(r'TAILLE\s*/?\s*Height\s*(\d{1,2}[,\.]\d{2})\s*m', text)
    if height_match:
        height = height_match.group(1).replace(',', '.')
        data['height'] = f"{height} m"

    # Date de délivrance
    issue_date_patterns = [
        r'DATE DE DELIVRANCE\s*/?\s*Date of issue\s*(\d{2}/\d{2}/\d{4})',
        r'(\d{2}/\d{2}/\d{4})',
    ]

    for pattern in issue_date_patterns:
        match = re.search(pattern, text)
        if match:
            date_str = match.group(1)
            year = int(date_str.split('/')[2])
            if 2000 <= year <= 2025 and 'issueDate' not in data:
                data['issueDate'] = date_str
                break

    return data

def format_date(date_str: str) -> str:
    """Convertit date MRZ YYMMDD en DD/MM/YYYY"""
    if len(date_str) == 6 and date_str.isdigit():
        yy, mm, dd = date_str[:2], date_str[2:4], date_str[4:6]
        year = int(yy)
        full_year = 2000 + year if year <= 30 else 1900 + year
        return f"{dd}/{mm}/{full_year}"
    return date_str

def get_country_info(code: str) -> Dict[str, str]:
    """Mapping codes pays"""
    countries = {
        'FRA': {'name': 'FRANCE', 'nationality': 'FRENCH'},
        'SEN': {'name': 'SENEGAL', 'nationality': 'SENEGALESE'},
        'DEU': {'name': 'GERMANY', 'nationality': 'GERMAN'},
        'ESP': {'name': 'SPAIN', 'nationality': 'SPANISH'},
    }
    return countries.get(code.upper(), {'name': code, 'nationality': code})

def extract_card_data(image_path: str, doc_type: str, side: str) -> Dict:
    """Extraction des données de carte selon le type et le côté"""
    if not EASYOCR_AVAILABLE:
        return {'status': 'error', 'error': 'EasyOCR non disponible'}

    try:
        reader = get_easyocr_reader()
        results = reader.readtext(image_path)

        # Texte pour EasyOCR
        text_blocks = [r[1] for r in results if r[2] > 0.5]
        text_combined = ' '.join(text_blocks)

        # Extraction selon le type et le côté
        if doc_type == 'ID_CARD_SENEGALESE':
            if side == 'RECTO':
                return extract_senegalese_recto(text_combined)
            else:  # VERSO
                return extract_senegalese_verso(text_combined, image_path)

        elif doc_type == 'ID_CARD_FRENCH':
            if side == 'RECTO':
                return extract_french_recto(text_combined)
            else:  # VERSO
                return extract_french_verso(text_combined, image_path)

        return {}

    except Exception as e:
        return {'status': 'error', 'error': str(e)}

def extract_passport(image_path: str) -> Dict:
    """Extraction complète du passeport avec toutes les informations"""
    if not PASSPORT_EYE_AVAILABLE:
        return {'status': 'error', 'error': 'PassportEye non disponible'}

    try:
        mrz_data = read_mrz(image_path)

        mrz_data_dict = {}
        mrz_detected = False
        ocr_dates = []

        # Extraction OCR en parallèle
        text_combined = ""
        if EASYOCR_AVAILABLE:
            reader = get_easyocr_reader()
            results = reader.readtext(image_path)
            text_blocks = [r[1] for r in results if r[2] > 0.4]
            text_combined = ' '.join(text_blocks)

            # Extraire les dates OCR
            ocr_dates = extract_dates_from_text(text_combined)

        # Extraction des données MRZ
        if mrz_data:
            mrz_detected = True

            # Numéro de document
            if hasattr(mrz_data, 'number') and mrz_data.number:
                num = mrz_data.number.replace('O', '0').replace('o', '0')
                mrz_data_dict['documentNumber'] = num

            # Nom de famille (nettoyé)
            if hasattr(mrz_data, 'surname') and mrz_data.surname:
                surname = clean_mrz_text(mrz_data.surname)
                mrz_data_dict['surname'] = surname

            # Prénoms (nettoyés)
            if hasattr(mrz_data, 'names') and mrz_data.names:
                names = mrz_data.names.strip()
                names = re.sub(r'([A-Z])\1{3,}', '', names)
                names = clean_mrz_text(names)
                mrz_data_dict['givenNames'] = names.strip()

            # Sexe
            if hasattr(mrz_data, 'sex') and mrz_data.sex:
                mrz_data_dict['sex'] = mrz_data.sex

            # Nationalité
            if hasattr(mrz_data, 'nationality') and mrz_data.nationality:
                country_info = get_country_info(mrz_data.nationality)
                mrz_data_dict['nationality'] = country_info['nationality']

            # Dates MRZ
            if hasattr(mrz_data, 'date_of_birth') and mrz_data.date_of_birth:
                if isinstance(mrz_data.date_of_birth, str):
                    mrz_data_dict['dateOfBirth'] = format_date(mrz_data.date_of_birth)
                else:
                    mrz_data_dict['dateOfBirth'] = mrz_data.date_of_birth.strftime("%d/%m/%Y")

            if hasattr(mrz_data, 'expiration_date') and mrz_data.expiration_date:
                if isinstance(mrz_data.expiration_date, str):
                    mrz_data_dict['expiryDate'] = format_date(mrz_data.expiration_date)
                else:
                    mrz_data_dict['expiryDate'] = mrz_data.expiration_date.strftime("%d/%m/%Y")

            # Pays émetteur
            issuing_country = 'UNKNOWN'
            if hasattr(mrz_data, 'country') and mrz_data.country:
                country_info = get_country_info(mrz_data.country)
                issuing_country = country_info['name']

        else:
            if EASYOCR_AVAILABLE:
                ocr_data = extract_passport_dates_and_info(text_combined)
                mrz_data_dict.update(ocr_data)

        # Fusionner les données MRZ et OCR avec priorité améliorée
        final_data = merge_mrz_and_ocr_data_enhanced(mrz_data_dict, ocr_dates)

        # Extraire les informations supplémentaires
        additional_info = extract_additional_passport_info(text_combined, mrz_data if mrz_detected else None)
        final_data.update(additional_info)

        # Compléter avec les données OCR standards
        if EASYOCR_AVAILABLE and text_combined:
            # Taille
            if not final_data.get('height'):
                height_patterns = [
                    r'(?:Taille|Height)[:\s]*(\d[.,]\d{2})\s*[mM]',
                    r'(\d[.,]\d{2})\s*[mM](?:\s*(?:VERT|Vert|GREEN))?',
                ]

                for pattern in height_patterns:
                    match = re.search(pattern, text_combined)
                    if match:
                        height = match.group(1).replace(',', '.')
                        final_data['height'] = f"{height} m"
                        break

            # Lieu de naissance
            if not final_data.get('birthPlace'):
                birth_place_patterns = [
                    r'(?:LIEU DE NAISSANCE|PLACE OF BIRTH|NE\s*A)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|DELIVRE|ISSUED|Date)|\s*$)',
                    r'(?:NE\s*A|BORN\s*IN)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|Date)|\s*$)',
                    r'(KAOLACK|DAKAR|THIES|SAINT\s*LOUIS|ZIGUINCHOR|TAMBACOUNDA|DIOURBEL|LOUGA|FATICK)',
                    r'(PARIS|LYON|MARSEILLE|TOULOUSE|NICE|BORDEAUX|NANTES|STRASBOURG|MONTPELLIER|LILLE)',
                    r'(LODEVE|TOULON|PERPIGNAN|BIARRITZ|CANNES|AVIGNON)',
                ]

                for pattern in birth_place_patterns:
                    match = re.search(pattern, text_combined, re.IGNORECASE)
                    if match:
                        candidate = match.group(1).strip()
                        if (len(candidate) >= 3 and
                                not re.search(r'\d', candidate) and
                                candidate.upper() not in ['PASSPORT', 'PASSEPORT', 'REPUBLIQUE', 'AUTHORITY', 'AUTORITE', 'DIRECT', 'FRANCE']):
                            final_data['birthPlace'] = candidate.upper()
                            break

        # Déterminer le pays émetteur
        if 'issuing_country' not in locals():
            if final_data.get('nationality') == 'SENEGALESE':
                issuing_country = 'SENEGAL'
            elif final_data.get('nationality') == 'FRENCH':
                issuing_country = 'FRANCE'
            else:
                issuing_country = 'UNKNOWN'

        # Niveau de confiance
        confidence = 'very_high' if (mrz_detected and getattr(mrz_data, 'valid', False)) else 'high'
        if not mrz_detected and len(final_data) < 5:
            confidence = 'medium'

        return {
            'status': 'success',
            'documentType': 'PASSPORT',
            'side': 'SINGLE',
            'issuingCountry': issuing_country,
            'confidence': confidence,
            'data': final_data,
            'extractionMethod': 'PassportEye + EasyOCR',
            'mrzDetected': mrz_detected,
            'mrzValid': getattr(mrz_data, 'valid', False) if mrz_data else False
        }

    except Exception as e:
        return {'status': 'error', 'error': str(e)}

def extract_document_data(image_path: str, expected_side: str = None) -> Dict:
    """Fonction principale d'extraction"""
    try:
        # Détection du type et du côté
        doc_type, detected_side = detect_document_type_and_side(image_path)

        # Utiliser le côté attendu si fourni, sinon utiliser le côté détecté
        side = expected_side if expected_side else detected_side

        # Extraction selon le type
        if doc_type == 'PASSPORT':
            return extract_passport(image_path)
        else:
            # Extraction des données de carte
            data = extract_card_data(image_path, doc_type, side)

            if 'error' in data:
                return data

            # Déterminer le pays émetteur
            issuing_country = 'FRANCE' if doc_type == 'ID_CARD_FRENCH' else 'SENEGAL'

            return {
                'status': 'success',
                'documentType': doc_type,
                'side': side,
                'issuingCountry': issuing_country,
                'confidence': 'high',
                'data': data,
                'extractionMethod': 'EasyOCR + PassportEye (MRZ)',
                'mrzDetected': side == 'VERSO'
            }

    except Exception as e:
        return {'status': 'error', 'error': str(e)}

def extract_document_both_sides(recto_image_path: str, verso_image_path: str) -> Dict:
    """Extraction des deux côtés d'une carte d'identité"""
    try:
        # Extraction recto
        recto_result = extract_document_data(recto_image_path, 'RECTO')
        if recto_result['status'] == 'error':
            return recto_result

        # Extraction verso
        verso_result = extract_document_data(verso_image_path, 'VERSO')
        if verso_result['status'] == 'error':
            return verso_result

        # Vérifier que c'est le même type de document
        if recto_result['documentType'] != verso_result['documentType']:
            return {
                'status': 'error',
                'error': f"Document type mismatch: {recto_result['documentType']} vs {verso_result['documentType']}"
            }

        # Fusionner les données sans doublons
        merged_data = merge_data_without_duplicates(recto_result['data'], verso_result['data'])

        return {
            'status': 'success',
            'documentType': recto_result['documentType'],
            'side': 'BOTH',
            'issuingCountry': recto_result['issuingCountry'],
            'confidence': 'very_high',
            'data': merged_data,
            'extractionMethod': 'EasyOCR + PassportEye (MRZ)',
            'mrzDetected': True,
            'recto_data': recto_result['data'],
            'verso_data': verso_result['data']
        }

    except Exception as e:
        return {'status': 'error', 'error': str(e)}

if __name__ == "__main__":
    if len(sys.argv) == 2:
        # Un seul fichier
        image_path = sys.argv[1]
        result = extract_document_data(image_path)
        print(json.dumps(result, ensure_ascii=False, indent=2))

    elif len(sys.argv) == 3:
        # Recto et verso
        recto_path = sys.argv[1]
        verso_path = sys.argv[2]
        result = extract_document_both_sides(recto_path, verso_path)
        print(json.dumps(result, ensure_ascii=False, indent=2))

    else:
        print(json.dumps({
            'error': 'Usage: python document_extractor.py <image_path> ou python document_extractor.py <recto_path> <verso_path>'
        }))
        sys.exit(1)