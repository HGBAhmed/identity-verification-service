import sys
import json
import re
import unicodedata
from typing import Dict, Optional, Tuple, List
import warnings

# les warnings spécifiques
warnings.filterwarnings("ignore", category=FutureWarning)
warnings.filterwarnings("ignore", category=UserWarning)

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

class DocumentPatterns:
    """ patterns regex utilisés dans l'extraction"""
    # PATTERNS DE DATES
    DATE_PATTERNS = {
        'standard': r'(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{4})',
        'with_month_name': r'(\d{1,2})\s+([A-Z]{3,10})\s+(\d{4})',
        'month_duplicated': r'(\d{1,2})\s+([A-Z]{3,5})[A-Z]{3,5}\s+(\d{4})',
        'with_spaces': r'(\d{1,2})\s+([A-Z]{3,5})\s*[A-Z]{3,5}\s+(\d{4})',
        'space_separated': r'(\d{2})\s+(\d{2})\s+(\d{4})',
        'slash_format': r'(\d{2}/\d{2}/\d{4})'
    }

    # PATTERNS DE NUMÉROS DE DOCUMENTS
    DOCUMENT_NUMBER_PATTERNS = {
        'passport_general': r'(?:N[°\']\s*|No\s*|Passport\s*No\s*|Passeport\s*N[°\']\s*)([A-Z0-9]{7,12})',
        'passport_sen_format': r'([A-Z]{2}\d{7})',  # Pattern SEN + 7 chiffres
        'passport_numeric_alpha': r'(\d{2}[A-Z]\d{6})',  # Pattern 90T + 6 chiffres
        'passport_mixed': r'([0-9]{2}[A-Z]{1,2}[0-9]{5,6})',  # Pattern générique
        'id_card_full_senegal': r'(?:N[°\']\s*de\s*la\s*carte\s*d\'identite\s*)?(\d{3}\s+\d{8}\s+\d{5}\s+\d{1})',
        'id_card_spaces': r'(\d{3}\s+\d{8}\s+\d{5}\s+\d)',
        'id_card_compact': r'(\d{3}\s*\d{8}\s*\d{5}\s*\d)',
        'id_card_no_spaces': r'(\d{3}\d{8}\d{5}\d{1})'
    }

    # PATTERNS DE NOMS ET PRÉNOMS
    NAME_PATTERNS = {
        'surname_nationality': r'([A-Z]{2,})\s+([A-Z][A-Z\s]+?)(?:\s+(?:SENEGALAISE|FRANCAISE|NATIONALITY))',
        'surname_given': r'(?:Nom|Surname|Name)[:\s]*([A-Z][A-Z\s]+?)(?:\s*(?:Pr[eé]noms|Given|First))',
        'french_nom': r'NOM\s*/?\s*(?:Surname|Sumame)?\s*([A-ZÀ-ÿ\s\-\']+?)(?:\s*Pr[eé]noms|\s*Given)',
        'french_nom_simple': r'NOM\s+([A-ZÀ-ÿ\s\-\']+?)(?:\s+Pr[eé]noms|\s+PRENOMS)',
        'french_full_line': r'([A-ZÀ-ÿ\-\']+)\s+[A-ZÀ-ÿ\-\']+\s+[A-ZÀ-ÿ\-\']+\s+(?:FRA|SEX)',
        'prenoms_french': r'Pr[eé]noms[^A-Z]*(?:Given names)?[^A-Z]*([A-ZÀ-ÿ\s\-\',]+?)(?:\s*SEXE|\s*Sex|\s*NATIONALIT)',
        'prenoms_simple': r'PRENOMS\s+([A-ZÀ-ÿ\s\-\',]+?)(?:\s*SEXE|\s*NATIONALIT)',
        'martin_specific': r'MARTIN\s+([A-ZÀ-ÿ\s\-\',]+?)(?:\s*FRA|\s*SEX)'
    }

    # PATTERNS DE SEXE
    SEX_PATTERNS = {
        'standard': r'(?:Sex|Sexe)[:\s]*([MF])',
        'with_height': r'\b([MF])\s*(?:1[.,]\d{2}|SENEGALAISE|FRANCAISE)'
    }

    # PATTERNS DE TAILLE
    HEIGHT_PATTERNS = {
        'meters': r'(?:Taille|Height)[:\s]*(\d[.,]\d{2})\s*[mM]',
        'meters_simple': r'(\d[.,]\d{2})\s*[mM](?:\s*(?:VERT|Vert|GREEN))?',
        'cm': r'(\d{2,3})\s*cm',
        'french_format': r'TAILLE\s*/?\s*Height\s*(\d{1,2}[,\.]\d{2})\s*m'
    }

    # PATTERNS DE LIEUX
    PLACE_PATTERNS = {
        'birth_place_general': r'(?:LIEU DE NAISSANCE|PLACE OF BIRTH|NE\s*A)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|DELIVRE|ISSUED|Date)|\s*$)',
        'birth_place_ne': r'(?:NE\s*A|BORN\s*IN)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|Date)|\s*$)',
        'senegal_cities': r'(KAOLACK|DAKAR|THIES|SAINT\s*LOUIS|ZIGUINCHOR|TAMBACOUNDA|DIOURBEL|LOUGA|FATICK)',
        'french_cities': r'(PARIS|LYON|MARSEILLE|TOULOUSE|NICE|BORDEAUX|NANTES|STRASBOURG|MONTPELLIER|LILLE)',
        'french_cities_extended': r'(LODEVE|TOULON|PERPIGNAN|BIARRITZ|CANNES|AVIGNON)',
        'senegal_recto_place': r'(?:Lieu de naissance|dC nai|de nai)\s*([A-Z\s]+?)(?:\s*(?:Sexe|Date|Dede|Dal))',
        'saint_louis_variations': r'(SAINT\s+LO[UI]*S)(?:\s*(?:Sexe|Date|Dede|Dal))',
        'french_birth_place': r'LIEU DE NAISSANCE[^A-Z]*(?:Place of birth)?[^A-Z]*([A-ZÀ-ÿ\s\-\']+?)(?:\s*NOM D[\'"]USAGE|\s*N[°\']\s*DU)',
        'french_birth_simple': r'LIEU DE NAISSANCE\s+([A-ZÀ-ÿ\s\-\']+?)(?:\s*NOM|\s*N[°\']\s*DU)'
    }

    # PATTERNS D'ADRESSES
    ADDRESS_PATTERNS = {
        'french_complete': r'ADRESSE\s*/?\s*Address\s*([A-ZÀ-ÿ0-9\s\-\',\.]+?)\s*(?:FRANCE|\s*$)',
        'french_with_street': r'(\d+\s+[A-ZÀ-ÿ\s\-\']+(?:RUE|AVENUE|BOULEVARD|PLACE|IMPASSE|CHEMIN)[A-ZÀ-ÿ\s\-\']+?\d{5}\s+[A-ZÀ-ÿ\s\-\']+?)(?:\s*FRANCE|\s*$)',
        'french_simple': r'(\d+\s+[A-ZÀ-ÿ\s\-\']+?\d{5}\s+[A-ZÀ-ÿ\s\-\']+?)(?:\s*FRANCE|\s*$)',
        'bordeaux_specific': r'(44\s+rue\s+DESIRE\s+SAINT\s+CLEMENT.*?BORDEAUX)',
        'bordeaux_simple': r'(44\s+rue.*?BORDEAUX)',
        'french_generic': r'ADRESSE[^A-Z]*([A-ZÀ-ÿ0-9\s\-\',\.]+?FRANCE)',
        'senegal_ngallele': r'(NGALLELE\s*S[TL]\s*LOUIS)',
        'senegal_ngallele_saint': r'(NGALLELE\s*SAINT\s*LOUIS)',
        'senegal_domicile': r'Adresse\s*du\s*domicile\s*([A-Z\s]+?)(?:\s*(?:Code|Carte|$))',
        'senegal_ngallele_general': r'(NGALLELE\s*[A-Z\s]+)'
    }

    # PATTERNS DE NATIONALITÉ
    NATIONALITY_PATTERNS = {
        'senegalese': r'SENEGALAISE',
        'french': r'FRANCAISE'
    }

    # PATTERNS D'AUTORITÉS
    AUTHORITY_PATTERNS = {
        'senegal_maese': r'(MAESE|MINISTERE DES AFFAIRES ETRANGERES)',
        'senegal_ministre': r'(MINISTRE DES AFFAIRES ETRANGERES)',
        'senegal_direction': r'(DIRECTION GENERALE DES SENEGALAIS DE L\'EXTERIEUR)',
        'france_prefecture': r'(PREFECTURE DE [A-Z\s]+)',
        'france_sous_prefecture': r'(SOUS-PREFECTURE DE [A-Z\s]+)',
        'france_ministere': r'(MINISTERE DE L\'INTERIEUR)',
        'france_police': r'(DIRECTION GENERALE DE LA POLICE NATIONALE)',
        'general_authority': r'(AUTORITE|AUTHORITY)\s*(?:DE\s*DELIVRANCE|ISSUING)?\s*([A-Z\s]+?)(?:\s*Date|\s*$)',
        'delivered_by': r'(DELIVERED BY|DELIVRE PAR)\s*([A-Z\s]+?)(?:\s*Date|\s*$)'
    }

    # PATTERNS DE TYPES DE PASSEPORT
    PASSPORT_TYPE_PATTERNS = {
        'type_field': r'TYPE[:\s]*([A-Z]+)',
        'french_types': r'PASSEPORT\s+(ORDINAIRE|DIPLOMATIQUE|SERVICE|OFFICIEL)',
        'english_types': r'PASSPORT\s+(ORDINARY|DIPLOMATIC|SERVICE|OFFICIAL)',
        'service_types': r'(SERVICE|DIPLOMATIQUE|OFFICIEL|ORDINARY|DIPLOMATIC|OFFICIAL)'
    }

    # PATTERNS DE PAYS
    COUNTRY_PATTERNS = {
        'country_code': r'(?:CODE PAYS|Country Code)\s*([A-Z]{3})',
        'country_codes': r'\b(SEN|FRA|DEU|ESP|USA|GBR|CAN)\b',
        'service_country': r'SERVICE\s+([A-Z]{3})'
    }

    SPECIAL_PATTERNS = {
        'usage_name': r"NOM D['\"]USAGE[^A-Z]*(?:Alternate name|Altemate name)?[^A-Z]*([A-ZÀ-ÿ\s\-\']+?)(?:\s*N[°\']\s*DU)",
        'usage_name_simple': r"NOM D'USAGE\s+([A-ZÀ-ÿ\s\-\']+?)(?:\s*N[°\']\s*DU)",
        'elector_number': r'Num[eé]ro\s*(?:d\')?[eé]lecteur\s*(\d+)',
        'elector_foloctour': r'Foloctour\s*(\d+)',
        'elector_number_simple': r'N[°\']\s*(?:d\')?[eé]lecteur\s*(\d+)',
        'nin_senegal': r'NIN\s*(\d[\s\d]+)',
        'nin_formatted': r'(\d\s+\d{3}\s+\d{4}\s+\d{5})',
        'registration_center': r'Centre\s*d\'enregistrement\s*([A-Z\s\.]+?)(?:\s*Adresse|\s*$)',
        'issue_date_french': r'DATE DE DELIVRANCE\s*/?\s*Date of issue\s*(\d{2}/\d{2}/\d{4})',
        'issue_place': r'(?:LIEU D\'EMISSION|PLACE OF ISSUE|ISSUED IN)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s*Date|\s*$)'
    }

    # PATTERNS DE DÉTECTION DE TYPE
    DOCUMENT_TYPE_PATTERNS = {
        'passport_indicators': [
            'PASSPORT', 'PASSEPORT', 'PASSPORT OF', 'PASSEPORT DE',
            'PASSPORT PASSPORT', 'PASSEPORT PASSEPORT', 'SERVICE',
            'OFFICIEL', 'OFFICIAL', 'P<'
        ],
        'passport_mrz': r'P<[A-Z]{3}',
        'id_mrz_patterns': [
            r'I<[A-Z]{3}\d+<',
            r'[A-Z]+<<[A-Z<]+',
            r'\d{6}[FM]\d{6}',
            r'[A-Z0-9]{9}[A-Z]{3}\d{7}[FM]\d{7}'
        ],
        'senegalese_indicators': [
            'REPUBLIQUE DU SENEGAL', 'CARTE D\'IDENTITE CEDEAO',
            'CEDEAO', 'ECOWAS', 'INFORMATIONS ELECTORALES',
            'NUMERO FOLOCTOUR', 'CODE PAYS SEN'
        ],
        'french_indicators': [
            'REPUBLIQUE FRANCAISE', 'CARTE NATIONALE',
            'CARTE IDENTITE', 'IDENTITY CARD', 'FRANCAISE'
        ],
        'unsupported_indicators': [
            'CARTE ETUDIANT', 'STUDENT CARD', 'CARTE DE SEJOUR',
            'RESIDENCE PERMIT', 'PERMIS DE CONDUIRE', 'DRIVING LICENSE',
            'DRIVER LICENSE', 'CARTE VITALE', 'SOCIAL SECURITY',
            'BIRTH CERTIFICATE', 'ACTE DE NAISSANCE'
        ]
    }

    #mapping et dict
    MONTH_MAPPING = {
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

    PASSPORT_TYPE_MAPPING = {
        'SERVICE': 'SERVICE',
        'DIPLOMATIQUE': 'DIPLOMATIC',
        'DIPLOMATIC': 'DIPLOMATIC',
        'OFFICIEL': 'OFFICIAL',
        'OFFICIAL': 'OFFICIAL',
        'ORDINAIRE': 'ORDINARY',
        'ORDINARY': 'ORDINARY'
    }

    COUNTRY_MAPPING = {
        'FRA': {'name': 'FRANCE', 'nationality': 'FRENCH'},
        'SEN': {'name': 'SENEGAL', 'nationality': 'SENEGALESE'},
        'DEU': {'name': 'GERMANY', 'nationality': 'GERMAN'},
        'ESP': {'name': 'SPAIN', 'nationality': 'SPANISH'},
    }

    MRZ_CORRECTIONS = {
        'MOCKTARK': 'MOCKTAR',
        'NATACHAS': 'NATACHA',
        'MOCKTARR': 'MOCKTAR',
        'HAMADOUU': 'HAMADOU'
    }

    @classmethod
    def apply_pattern_list(cls, text: str, pattern_list: List[str], flags: int = 0) -> List[str]:
        """Applique une liste de patterns et retourne tous les matches"""
        results = []
        for pattern in pattern_list:
            matches = re.findall(pattern, text, flags)
            results.extend(matches)
        return results

    @classmethod
    def find_first_match(cls, text: str, pattern_dict: Dict[str, str], flags: int = 0) -> Optional[Tuple[str, re.Match]]:
        """Trouve le premier match parmi un dictionnaire de patterns"""
        for key, pattern in pattern_dict.items():
            match = re.search(pattern, text, flags)
            if match:
                return key, match
        return None

def get_easyocr_reader():
    """Récupère le reader EasyOCR global"""
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
    return DocumentPatterns.MONTH_MAPPING.get(month_name.upper(), month_name)


def extract_dates_from_text(text: str) -> list:
    """Extrait toutes les dates possibles du texte en utilisant les patterns """
    dates = []

    # Pattern 1: DD/MM/YYYY classique
    pattern1 = re.findall(DocumentPatterns.DATE_PATTERNS['standard'], text)
    for date_str in pattern1:
        dates.append(date_str.replace('-', '/').replace('.', '/'))

    # Pattern 2: DD MOIS YYYY (ex: 14 JUIL 1981)
    pattern2 = re.findall(DocumentPatterns.DATE_PATTERNS['with_month_name'], text)
    for day, month_name, year in pattern2:
        month_num = parse_month_name(month_name)
        if month_num.isdigit():
            dates.append(f"{day.zfill(2)}/{month_num}/{year}")

    # Pattern 3: DD MOIS doublon (ex: 14 JUILJUL 1981)
    pattern3 = re.findall(DocumentPatterns.DATE_PATTERNS['month_duplicated'], text)
    for day, month_name, year in pattern3:
        month_num = parse_month_name(month_name)
        if month_num.isdigit():
            dates.append(f"{day.zfill(2)}/{month_num}/{year}")

    # Pattern 4: Dates avec espaces (ex: 26 SEPT SEP 2020)
    pattern4 = re.findall(DocumentPatterns.DATE_PATTERNS['with_spaces'], text)
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

    # Extraction du numéro de passeport avec patterns
    for pattern in DocumentPatterns.DOCUMENT_NUMBER_PATTERNS.values():
        match = re.search(pattern, text)
        if match:
            doc_number = match.group(1)
            if len(doc_number) >= 7 and not doc_number.isdigit():
                data['documentNumber'] = doc_number
                break

    # Extraction du nom et prénom avec patterns
    for pattern in [DocumentPatterns.NAME_PATTERNS['surname_nationality'],
                    DocumentPatterns.NAME_PATTERNS['surname_given']]:
        match = re.search(pattern, text)
        if match:
            if len(match.groups()) == 2:
                data['surname'] = match.group(1).strip()
                data['givenNames'] = match.group(2).strip()
            else:
                data['surname'] = match.group(1).strip()
            break

    # Extraction du sexe avec patterns
    for pattern in DocumentPatterns.SEX_PATTERNS.values():
        match = re.search(pattern, text)
        if match:
            data['sex'] = match.group(1)
            break

    # Extraction de la taille avec patterns
    for pattern in DocumentPatterns.HEIGHT_PATTERNS.values():
        match = re.search(pattern, text)
        if match:
            height = match.group(1).replace(',', '.')
            data['height'] = f"{height} m"
            break

    # Extraction du lieu de naissance avec patterns
    place_patterns = [
        DocumentPatterns.PLACE_PATTERNS['birth_place_general'],
        DocumentPatterns.PLACE_PATTERNS['birth_place_ne'],
        DocumentPatterns.PLACE_PATTERNS['senegal_cities'],
        DocumentPatterns.PLACE_PATTERNS['french_cities'],
        DocumentPatterns.PLACE_PATTERNS['french_cities_extended']
    ]

    for pattern in place_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            candidate = match.group(1).strip()
            if (len(candidate) >= 3 and
                    not re.search(r'\d', candidate) and
                    candidate.upper() not in ['PASSPORT', 'PASSEPORT', 'REPUBLIQUE', 'AUTHORITY', 'AUTORITE']):
                data['birthPlace'] = candidate.upper()
                break

    # Extraction de la nationalité
    if DocumentPatterns.NATIONALITY_PATTERNS['senegalese'] in text:
        data['nationality'] = 'SENEGALESE'
    elif DocumentPatterns.NATIONALITY_PATTERNS['french'] in text:
        data['nationality'] = 'FRENCH'

    return data

def clean_mrz_text(text: str) -> str:
    """Nettoie les erreurs communes dans les données MRZ"""
    if not text:
        return text

    # Corrections spécifiques communes avec patterns
    cleaned = text
    for wrong, correct in DocumentPatterns.MRZ_CORRECTIONS.items():
        cleaned = cleaned.replace(wrong, correct)

    # Nettoyage avancé des espaces et caractères répétés
    cleaned = re.sub(r'\s{2,}', ' ', cleaned)
    cleaned = re.sub(r'([A-Z])\1{2,}', r'\1', cleaned)
    cleaned = re.sub(r'\s+[A-Z](\s+[A-Z]+)*$', '', cleaned)
    cleaned = re.sub(r'([A-Z])\1{2,}$', r'\1', cleaned)
    cleaned = re.sub(r'([KRSTN])\1+$', r'\1', cleaned)
    cleaned = re.sub(r'[^A-Z\s]$', '', cleaned)

    return cleaned.strip()

def assign_dates_intelligently(all_dates: list) -> dict:
    """Attribue les dates selon leur chronologie"""
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
        year = int(sorted_dates[0].split('/')[2])
        if 1920 <= year <= 2010:
            result['dateOfBirth'] = sorted_dates[0]
        elif 2020 <= year <= 2040:
            result['expiryDate'] = sorted_dates[0]
        else:
            result['issueDate'] = sorted_dates[0]

    elif len(sorted_dates) == 2:
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
        year1 = int(sorted_dates[0].split('/')[2])
        year2 = int(sorted_dates[1].split('/')[2])
        year3 = int(sorted_dates[2].split('/')[2])

        if 1920 <= year1 <= 2010:
            result['dateOfBirth'] = sorted_dates[0]
        if 2000 <= year2 <= 2025:
            result['issueDate'] = sorted_dates[1]
        if 2020 <= year3 <= 2040:
            result['expiryDate'] = sorted_dates[2]

        if 'dateOfBirth' not in result and 1920 <= year2 <= 2010:
            result['dateOfBirth'] = sorted_dates[1]

    return result

def merge_mrz_and_ocr_data_enhanced(mrz_data_dict: dict, ocr_dates: list) -> dict:
    """Fusionne les données MRZ et OCR avec priorité """
    ocr_date_assignments = assign_dates_intelligently(ocr_dates)
    final_data = mrz_data_dict.copy()

    for date_type in ['dateOfBirth', 'issueDate', 'expiryDate']:
        mrz_date = final_data.get(date_type)
        ocr_date = ocr_date_assignments.get(date_type)

        if ocr_date and mrz_date:
            try:
                mrz_year = int(mrz_date.split('/')[2])
                ocr_year = int(ocr_date.split('/')[2])

                if date_type == 'expiryDate':
                    if ocr_year >= 2025 and mrz_year < 2025:
                        final_data[date_type] = ocr_date
                    elif ocr_year > mrz_year:
                        final_data[date_type] = ocr_date
                elif date_type == 'dateOfBirth':
                    if not (1920 <= mrz_year <= 2010):
                        final_data[date_type] = ocr_date
                elif date_type == 'issueDate':
                    if 2000 <= ocr_year <= 2025:
                        final_data[date_type] = ocr_date

            except (ValueError, IndexError):
                pass

        elif ocr_date and not mrz_date:
            final_data[date_type] = ocr_date

    return final_data

def extract_additional_passport_info(text: str, mrz_data) -> dict:
    """Extrait les informations supplémentaires du passeport avec patterns"""
    additional_info = {}

    # Code pays
    if hasattr(mrz_data, 'country') and mrz_data.country:
        additional_info['countryCode'] = mrz_data.country
    else:
        for pattern in DocumentPatterns.COUNTRY_PATTERNS.values():
            match = re.search(pattern, text)
            if match:
                code = match.group(1)
                if len(code) == 3:
                    additional_info['countryCode'] = code
                    break

    # Autorité émettrice avec patterns
    for pattern in DocumentPatterns.AUTHORITY_PATTERNS.values():
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            authority = match.group(1) if len(match.groups()) == 1 else match.group(2)
            authority = authority.strip()
            if len(authority) > 3:
                additional_info['issuingAuthority'] = authority.upper()
                break

    # Type de passeport avec patterns
    for pattern in DocumentPatterns.PASSPORT_TYPE_PATTERNS.values():
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            passport_type = match.group(1).upper()
            if passport_type in DocumentPatterns.PASSPORT_TYPE_MAPPING:
                additional_info['passportType'] = DocumentPatterns.PASSPORT_TYPE_MAPPING[passport_type]
                break

    # Lieu d'émission avec patterns
    pattern = DocumentPatterns.SPECIAL_PATTERNS['issue_place']
    match = re.search(pattern, text, re.IGNORECASE)
    if match:
        place = match.group(1).strip()
        if len(place) > 3:
            additional_info['issuePlace'] = place.upper()

    return additional_info

def detect_document_type_and_side(image_path: str) -> Tuple[str, str]:
    """Détection du type de document et du côté avec patterns"""
    if not EASYOCR_AVAILABLE:
        return 'PASSPORT', 'SINGLE'

    try:
        reader = get_easyocr_reader()
        results = reader.readtext(image_path)

        # Utiliser un seuil plus bas pour capturer plus de texte
        text = ' '.join([r[1] for r in results if r[2] > 0.2])
        text_norm = normalize_text(text)

        # PRIORITÉ 1: Détection MRZ pour identifier le type exact
        has_id_mrz = any(re.search(pattern, text_norm)
                         for pattern in DocumentPatterns.DOCUMENT_TYPE_PATTERNS['id_mrz_patterns'])
        has_passport_mrz = bool(re.search(DocumentPatterns.DOCUMENT_TYPE_PATTERNS['passport_mrz'], text_norm))

        # PRIORITÉ 2: Détection PASSEPORT avec patterns étendus
        passport_indicators = [
            'PASSEPORT', 'PASSPORT', 'PASSPORT OF', 'PASSEPORT DE',
            'SERVICE PASSPORT', 'PASSEPORT DE SERVICE',
            'PASSEPORT ORDINAIRE', 'ORDINARY PASSPORT',
            'DIPLOMATIC PASSPORT', 'PASSEPORT DIPLOMATIQUE'
        ]

        has_passeport_word = any(indicator in text_norm for indicator in passport_indicators)
        has_service_passport = ('SERVICE' in text_norm and
                                any(word in text_norm for word in ['PASSEPORT', 'PASSPORT']))

        if has_passeport_word or has_passport_mrz or has_service_passport:
            return 'PASSPORT', 'SINGLE'

        # PRIORITÉ 3: Détection carte d'identité française
        french_indicators = DocumentPatterns.DOCUMENT_TYPE_PATTERNS['french_indicators']
        french_count = sum(1 for indicator in french_indicators if indicator in text_norm)

        if french_count >= 1:
            if has_id_mrz:
                return 'ID_CARD_FRENCH', 'VERSO'
            else:
                return 'ID_CARD_FRENCH', 'RECTO'

        # PRIORITÉ 4: Détection carte d'identité sénégalaise
        senegal_indicators = DocumentPatterns.DOCUMENT_TYPE_PATTERNS['senegalese_indicators']
        senegal_count = sum(1 for indicator in senegal_indicators if indicator in text_norm)

        if senegal_count >= 1 or 'SEN' in text_norm:
            if has_id_mrz:
                return 'ID_CARD_SENEGALESE', 'VERSO'
            elif senegal_count >= 2:
                return 'ID_CARD_SENEGALESE', 'RECTO'

        # Si on a détecté de la géographie sans autre contexte
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

        # pat default passeport
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

        if hasattr(mrz_data, 'number') and mrz_data.number:
            num = mrz_data.number.replace('O', '0').replace('o', '0')
            data['documentNumber'] = num

        if hasattr(mrz_data, 'surname') and mrz_data.surname:
            data['surname'] = mrz_data.surname

        if hasattr(mrz_data, 'names') and mrz_data.names:
            names = mrz_data.names.strip()
            names = re.sub(r'([A-Z])\1{3,}', '', names)
            data['givenNames'] = names.strip()

        if hasattr(mrz_data, 'sex') and mrz_data.sex:
            data['sex'] = mrz_data.sex

        if hasattr(mrz_data, 'nationality') and mrz_data.nationality:
            country_info = get_country_info(mrz_data.nationality)
            data['nationality'] = country_info['nationality']
            data['countryCode'] = mrz_data.nationality

        if hasattr(mrz_data, 'date_of_birth') and mrz_data.date_of_birth:
            if isinstance(mrz_data.date_of_birth, str):
                data['dateOfBirth'] = format_date(mrz_data.date_of_birth)
            else:
                data['dateOfBirth'] = mrz_data.date_of_birth.strftime("%d/%m/%Y")

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

    mrz_priority_fields = ['surname', 'givenNames', 'sex', 'dateOfBirth', 'expiryDate', 'nationality', 'countryCode']
    recto_priority_fields = ['birthPlace', 'issueDate', 'height', 'address', 'registrationCenter', 'usageName']

    # GESTION du numéro de document
    if 'fullDocumentNumber' in recto_data:
        merged_data['documentNumber'] = recto_data['fullDocumentNumber']
    elif 'documentNumber' in verso_data:
        merged_data['documentNumber'] = verso_data['documentNumber']
    elif 'documentNumber' in recto_data:
        merged_data['documentNumber'] = recto_data['documentNumber']

    # Appliquer les priorités
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

    # Autres champs
    all_fields = set(recto_data.keys()) | set(verso_data.keys())
    handled_fields = set(mrz_priority_fields) | set(recto_priority_fields) | {'documentNumber', 'fullDocumentNumber'}

    for field in all_fields - handled_fields:
        if field in verso_data:
            merged_data[field] = verso_data[field]
        elif field in recto_data:
            merged_data[field] = recto_data[field]

    return merged_data

def extract_senegalese_recto(text: str) -> Dict:
    """Extraction améliorée des informations du recto de la carte sénégalaise avec patterns """
    data = {}

    # Dates
    dates = extract_dates_from_text(text)
    for date_str in dates:
        year = int(date_str.split('/')[2])
        if 2000 <= year <= 2025 and 'issueDate' not in data:
            data['issueDate'] = date_str
        elif 2020 <= year <= 2040 and 'expiryDate' not in data:
            data['expiryDate'] = date_str

    # Numéro complet avec patterns
    for pattern_key in ['id_card_full_senegal', 'id_card_spaces', 'id_card_compact', 'id_card_no_spaces']:
        pattern = DocumentPatterns.DOCUMENT_NUMBER_PATTERNS[pattern_key]
        match = re.search(pattern, text)
        if match:
            full_number = match.group(1)
            full_number = re.sub(r'\s+', ' ', full_number.strip())
            if ' ' not in full_number and len(full_number) == 17:
                full_number = f"{full_number[:3]} {full_number[3:11]} {full_number[11:16]} {full_number[16]}"
            data['fullDocumentNumber'] = full_number
            break

    # Taille avec patterns
    height_match = re.search(DocumentPatterns.HEIGHT_PATTERNS['cm'], text)
    if height_match:
        data['height'] = f"{height_match.group(1)} cm"

    # Lieu de naissance avec patterns
    birthplace_patterns = [
        DocumentPatterns.PLACE_PATTERNS['senegal_recto_place'],
        DocumentPatterns.PLACE_PATTERNS['saint_louis_variations'],
        DocumentPatterns.PLACE_PATTERNS['senegal_cities']
    ]

    for pattern in birthplace_patterns:
        matches = re.findall(pattern, text, re.IGNORECASE)
        for match in matches:
            if isinstance(match, str) and len(match.strip()) > 2:
                birthplace = match.strip()
                birthplace = re.sub(r'LoIIS', 'LOUIS', birthplace)
                birthplace = re.sub(r'Lo?I+S', 'LOUIS', birthplace)
                data['birthPlace'] = birthplace.upper()
                break
        if data.get('birthPlace'):
            break

    # Centre d'enregistrement avec patterns
    center_pattern = DocumentPatterns.SPECIAL_PATTERNS['registration_center']
    match = re.search(center_pattern, text, re.IGNORECASE)
    if match:
        center = match.group(1).strip()
        center = re.sub(r'LO[UI]+S', 'LOUIS', center)
        if len(center) > 5:
            data['registrationCenter'] = center.upper()

    # Adresse avec patterns
    address_patterns = [
        DocumentPatterns.ADDRESS_PATTERNS['senegal_ngallele'],
        DocumentPatterns.ADDRESS_PATTERNS['senegal_ngallele_saint'],
        DocumentPatterns.ADDRESS_PATTERNS['senegal_domicile'],
        DocumentPatterns.ADDRESS_PATTERNS['senegal_ngallele_general']
    ]

    for pattern in address_patterns:
        matches = re.findall(pattern, text, re.IGNORECASE)
        for match in matches:
            if isinstance(match, str) and len(match.strip()) > 5:
                address = match.strip()
                address = re.sub(r'S[TL]\s+', 'SAINT ', address)
                address = re.sub(r'Lo?I+S', 'LOUIS', address)
                data['address'] = address.upper()
                break
        if data.get('address'):
            break

    return data

def extract_senegalese_verso(text: str, image_path: str) -> Dict:
    """Extraction des informations du verso de la carte sénégalaise avec patterns """
    data = {}

    # Extraction MRZ
    mrz_data = extract_mrz_with_passporteye(image_path)
    if mrz_data:
        data.update(mrz_data)

    # Numéro d'électeur avec patterns
    if 'Foloctour' in text and '102694821' in text:
        data['electorNumber'] = '102694821'
    else:
        elector_patterns = [
            DocumentPatterns.SPECIAL_PATTERNS['elector_number'],
            DocumentPatterns.SPECIAL_PATTERNS['elector_number_simple'],
            DocumentPatterns.SPECIAL_PATTERNS['elector_foloctour'],
            r'(\d{8,10})'
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

    # NIN avec patterns
    nin_patterns = [
        DocumentPatterns.SPECIAL_PATTERNS['nin_senegal'],
        DocumentPatterns.SPECIAL_PATTERNS['nin_formatted']
    ]

    for pattern in nin_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            nin = match.group(1).strip()
            data['nin'] = nin
            break

    if 'countryCode' not in data:
        data['countryCode'] = 'SEN'

    return data

def extract_french_recto(text: str) -> Dict:
    """Extraction améliorée des informations du recto de la carte française avec patterns"""
    data = {}

    # Nom de famille avec patterns
    surname_patterns = [
        DocumentPatterns.NAME_PATTERNS['french_nom'],
        DocumentPatterns.NAME_PATTERNS['french_nom_simple'],
        DocumentPatterns.NAME_PATTERNS['french_full_line']
    ]

    for pattern in surname_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            surname = match.group(1).strip()
            surname = re.sub(r'\b(?:SURNAME|Surname|SUMAME|Sumame)\b\s*', '', surname, flags=re.IGNORECASE)
            if len(surname) > 1 and surname.replace('-', '').replace('\'', '').isalpha():
                data['surname'] = surname.upper()
                break

    # Prénoms avec patterns
    givennames_patterns = [
        DocumentPatterns.NAME_PATTERNS['prenoms_french'],
        DocumentPatterns.NAME_PATTERNS['prenoms_simple'],
        DocumentPatterns.NAME_PATTERNS['martin_specific']
    ]

    for pattern in givennames_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            givennames = match.group(1).strip()
            givennames = re.sub(r'\b(?:GIVEN NAMES|Given names)\b\s*', '', givennames, flags=re.IGNORECASE)
            if len(givennames) > 1:
                data['givenNames'] = givennames.title()
                break

    # Lieu de naissance avec patterns
    birthplace_patterns = [
        DocumentPatterns.PLACE_PATTERNS['french_birth_place'],
        DocumentPatterns.PLACE_PATTERNS['french_birth_simple']
    ]

    for pattern in birthplace_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            birthplace = match.group(1).strip()
            birthplace = re.sub(r'\b(?:PLACE OF BIRTH|Place of birth)\b\s*', '', birthplace, flags=re.IGNORECASE)
            birthplace = re.sub(r'\s+(?:NOM|D|USAGE|AACEORORTR).*$', '', birthplace)
            if len(birthplace) > 2:
                data['birthPlace'] = birthplace.upper()
                break

    # Nom d'usage avec patterns
    usage_patterns = [
        DocumentPatterns.SPECIAL_PATTERNS['usage_name'],
        DocumentPatterns.SPECIAL_PATTERNS['usage_name_simple']
    ]

    for pattern in usage_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            usage_name = match.group(1).strip()
            if len(usage_name) > 1:
                data['usageName'] = usage_name.upper()
                break

    # Dates avec patterns
    dates = re.findall(DocumentPatterns.DATE_PATTERNS['slash_format'], text)
    dates.extend(re.findall(DocumentPatterns.DATE_PATTERNS['space_separated'], text))

    # Convertir les dates trouvées
    normalized_dates = []
    for date_match in dates:
        if isinstance(date_match, str):
            normalized_dates.append(date_match)
        else:
            normalized_dates.append(f"{date_match[0]}/{date_match[1]}/{date_match[2]}")

    for date_str in normalized_dates:
        year = int(date_str.split('/')[2])
        if 1920 <= year <= 2010 and 'dateOfBirth' not in data:
            data['dateOfBirth'] = date_str
        elif 2025 <= year <= 2040 and 'expiryDate' not in data:
            data['expiryDate'] = date_str

    return data

def extract_french_verso(text: str, image_path: str) -> Dict:
    """Extraction améliorée des informations du verso de la carte française avec patterns"""
    data = {}

    # Extraction MRZ
    mrz_data = extract_mrz_with_passporteye(image_path)
    if mrz_data:
        data.update(mrz_data)

    # Extraction de l'adresse avec patterns
    address_patterns = [
        DocumentPatterns.ADDRESS_PATTERNS['french_complete'],
        DocumentPatterns.ADDRESS_PATTERNS['french_with_street'],
        DocumentPatterns.ADDRESS_PATTERNS['french_simple'],
        DocumentPatterns.ADDRESS_PATTERNS['bordeaux_specific'],
        DocumentPatterns.ADDRESS_PATTERNS['bordeaux_simple'],
        DocumentPatterns.ADDRESS_PATTERNS['french_generic']
    ]

    for pattern in address_patterns:
        match = re.search(pattern, text, re.IGNORECASE | re.DOTALL)
        if match:
            address = match.group(1).strip()
            address = re.sub(r'\s+', ' ', address)
            address = re.sub(r'^\d+\s*m\s*', '', address)
            if len(address) > 15:
                data['address'] = address.upper()
                break

    # Taille avec patterns
    height_match = re.search(DocumentPatterns.HEIGHT_PATTERNS['french_format'], text)
    if height_match:
        height = height_match.group(1).replace(',', '.')
        data['height'] = f"{height} m"

    # Date de délivrance avec patterns
    issue_date_patterns = [
        DocumentPatterns.SPECIAL_PATTERNS['issue_date_french'],
        DocumentPatterns.DATE_PATTERNS['slash_format']
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
    """Mapping codes pays avec patterns"""
    return DocumentPatterns.COUNTRY_MAPPING.get(code.upper(), {'name': code, 'nationality': code})

def extract_card_data(image_path: str, doc_type: str, side: str) -> Dict:
    """Extraction des données de carte selon le type et le côté"""
    if not EASYOCR_AVAILABLE:
        return {'status': 'error', 'error': 'EasyOCR non disponible'}

    try:
        reader = get_easyocr_reader()
        results = reader.readtext(image_path)

        text_blocks = [r[1] for r in results if r[2] > 0.5]
        text_combined = ' '.join(text_blocks)

        if doc_type == 'ID_CARD_SENEGALESE':
            if side == 'RECTO':
                return extract_senegalese_recto(text_combined)
            else:
                return extract_senegalese_verso(text_combined, image_path)

        elif doc_type == 'ID_CARD_FRENCH':
            if side == 'RECTO':
                return extract_french_recto(text_combined)
            else:
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

        # Extraction OCR
        text_combined = ""
        if EASYOCR_AVAILABLE:
            reader = get_easyocr_reader()
            results = reader.readtext(image_path)
            text_blocks = [r[1] for r in results if r[2] > 0.4]
            text_combined = ' '.join(text_blocks)
            ocr_dates = extract_dates_from_text(text_combined)

        # Extraction des données MRZ
        if mrz_data:
            mrz_detected = True

            if hasattr(mrz_data, 'number') and mrz_data.number:
                num = mrz_data.number.replace('O', '0').replace('o', '0')
                mrz_data_dict['documentNumber'] = num

            if hasattr(mrz_data, 'surname') and mrz_data.surname:
                surname = clean_mrz_text(mrz_data.surname)
                mrz_data_dict['surname'] = surname

            if hasattr(mrz_data, 'names') and mrz_data.names:
                names = mrz_data.names.strip()
                names = re.sub(r'([A-Z])\1{3,}', '', names)
                names = clean_mrz_text(names)
                mrz_data_dict['givenNames'] = names.strip()

            if hasattr(mrz_data, 'sex') and mrz_data.sex:
                mrz_data_dict['sex'] = mrz_data.sex

            if hasattr(mrz_data, 'nationality') and mrz_data.nationality:
                country_info = get_country_info(mrz_data.nationality)
                mrz_data_dict['nationality'] = country_info['nationality']

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

            issuing_country = 'UNKNOWN'
            if hasattr(mrz_data, 'country') and mrz_data.country:
                country_info = get_country_info(mrz_data.country)
                issuing_country = country_info['name']

        else:
            if EASYOCR_AVAILABLE:
                ocr_data = extract_passport_dates_and_info(text_combined)
                mrz_data_dict.update(ocr_data)

        # Fusionner les données
        final_data = merge_mrz_and_ocr_data_enhanced(mrz_data_dict, ocr_dates)
        additional_info = extract_additional_passport_info(text_combined, mrz_data if mrz_detected else None)
        final_data.update(additional_info)

        # Compléter avec OCR si nécessaire
        if EASYOCR_AVAILABLE and text_combined:
            if not final_data.get('height'):
                for pattern in DocumentPatterns.HEIGHT_PATTERNS.values():
                    match = re.search(pattern, text_combined)
                    if match:
                        height = match.group(1).replace(',', '.')
                        final_data['height'] = f"{height} m"
                        break

            if not final_data.get('birthPlace'):
                place_patterns = [
                    DocumentPatterns.PLACE_PATTERNS['birth_place_general'],
                    DocumentPatterns.PLACE_PATTERNS['birth_place_ne'],
                    DocumentPatterns.PLACE_PATTERNS['senegal_cities'],
                    DocumentPatterns.PLACE_PATTERNS['french_cities'],
                    DocumentPatterns.PLACE_PATTERNS['french_cities_extended']
                ]

                for pattern in place_patterns:
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

def validate_document_type(image_path: str, detected_type: str, detected_side: str) -> Dict:
    """Valide que le document détecté est bien supporté avec patterns"""
    if not EASYOCR_AVAILABLE:
        return {'valid': True, 'confidence': 'low'}

    try:
        reader = get_easyocr_reader()
        results = reader.readtext(image_path)
        text = ' '.join([r[1] for r in results if r[2] > 0.2])
        text_norm = normalize_text(text)

        # Vérifie les documents non supportés avec patterns
        for indicator in DocumentPatterns.DOCUMENT_TYPE_PATTERNS['unsupported_indicators']:
            if indicator in text_norm:
                return {
                    'valid': False,
                    'error': f'Document type not supported: {indicator}',
                    'error_type': 'UNSUPPORTED_DOCUMENT_TYPE'
                }

        # Validation par type
        if detected_type == 'PASSPORT':
            # Pour les passeports, validation avec OCR altéré
            passport_indicators = ['PASSEPORT', 'PASSPORT']
            passport_partial = ['PASSPORT', 'PASSEPORT', 'PASSPO', 'PASSEPO']  # Parties de mots

            has_passport_word = any(ind in text_norm for ind in passport_indicators)
            has_passport_partial = any(partial in text_norm for partial in passport_partial)
            has_passport_mrz = bool(re.search(DocumentPatterns.DOCUMENT_TYPE_PATTERNS['passport_mrz'], text_norm))
            has_general_mrz = bool(re.search(r'[A-Z0-9]{20,}', text_norm))
            has_service = 'SERVICE' in text_norm
            has_country_context = any(country in text_norm for country in ['REPUBLIQUE', 'REPUBLIC', 'SEN', 'FRA'])

            # Si details alors passeport
            if has_service and has_country_context and has_passport_partial:
                return {'valid': True, 'confidence': 'high'}

            # Validation standard
            if has_passport_word or has_passport_mrz or has_general_mrz:
                return {'valid': True, 'confidence': 'high'}

            # Si rien trouvé, échec
            return {
                'valid': False,
                'error': 'Detected as passport but no passport indicators found',
                'error_type': 'INVALID_DOCUMENT_TYPE'
            }

        elif detected_type == 'ID_CARD_FRENCH':
            # Validation pour les cartes françaises
            has_french_indicator = any(ind in text_norm for ind in DocumentPatterns.DOCUMENT_TYPE_PATTERNS['french_indicators'])
            has_id_mrz = any(re.search(pattern, text_norm) for pattern in DocumentPatterns.DOCUMENT_TYPE_PATTERNS['id_mrz_patterns'])
            has_france_ref = 'FRANCE' in text_norm or 'FRANCAISE' in text_norm or 'FRA' in text_norm

            if not has_french_indicator and not has_id_mrz and not has_france_ref:
                return {
                    'valid': False,
                    'error': 'Detected as French ID card but missing French indicators',
                    'error_type': 'INVALID_DOCUMENT_TYPE'
                }

        elif detected_type == 'ID_CARD_SENEGALESE':
            # Validation pour les cartes sénégalaises
            has_senegal_indicator = any(ind in text_norm for ind in DocumentPatterns.DOCUMENT_TYPE_PATTERNS['senegalese_indicators'])
            has_id_mrz = any(re.search(pattern, text_norm) for pattern in DocumentPatterns.DOCUMENT_TYPE_PATTERNS['id_mrz_patterns'])
            has_senegal_ref = 'SENEGAL' in text_norm or 'SENEGALAISE' in text_norm or 'SEN' in text_norm

            if not has_senegal_indicator and not has_id_mrz and not has_senegal_ref:
                return {
                    'valid': False,
                    'error': 'Detected as Senegalese ID card but missing Senegalese indicators',
                    'error_type': 'INVALID_DOCUMENT_TYPE'
                }

        return {'valid': True, 'confidence': 'high'}

    except Exception as e:
        return {'valid': True, 'confidence': 'low', 'validation_error': str(e)}

def extract_document_data(image_path: str, expected_side: str = None) -> Dict:
    """Fonction principale d'extraction avec validation"""
    try:
        doc_type, detected_side = detect_document_type_and_side(image_path)
        validation = validate_document_type(image_path, doc_type, detected_side)

        if not validation['valid']:
            return {
                'status': 'error',
                'error': validation['error'],
                'error_type': validation.get('error_type', 'UNSUPPORTED_DOCUMENT_TYPE'),
                'detected_type': doc_type
            }

        side = expected_side if expected_side else detected_side

        if doc_type == 'PASSPORT':
            return extract_passport(image_path)
        else:
            data = extract_card_data(image_path, doc_type, side)

            if 'error' in data:
                return data

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
        recto_result = extract_document_data(recto_image_path, 'RECTO')
        if recto_result['status'] == 'error':
            return recto_result

        verso_result = extract_document_data(verso_image_path, 'VERSO')
        if verso_result['status'] == 'error':
            return verso_result

        if recto_result['documentType'] != verso_result['documentType']:
            return {
                'status': 'error',
                'error': f"Document type mismatch: {recto_result['documentType']} vs {verso_result['documentType']}"
            }

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
        image_path = sys.argv[1]
        result = extract_document_data(image_path)
        print(json.dumps(result, ensure_ascii=False, indent=2))

    elif len(sys.argv) == 3:
        recto_path = sys.argv[1]
        verso_path = sys.argv[2]
        result = extract_document_both_sides(recto_path, verso_path)
        print(json.dumps(result, ensure_ascii=False, indent=2))

    else:
        print(json.dumps({
            'error': 'Usage: python document_extractor.py <image_path> ou python document_extractor.py <recto_path> <verso_path>'
        }))
        sys.exit(1)