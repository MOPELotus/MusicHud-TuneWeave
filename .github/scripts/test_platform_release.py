import io
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

import platform_release as platform


class PlatformContracts(unittest.TestCase):
    def test_api_paths_cannot_replace_origin_or_traverse(self):
        client = platform.Client('modrinth', 'test-token')
        for path in ('https://other.example/', '//other.example/', '../version', 'project/../version',
                     'project\\version', None):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'Invalid API path'):
                client.request(path)

    def test_control_characters_in_credential_are_rejected_without_echoing_it(self):
        with self.assertRaisesRegex(ValueError, '^Missing or invalid platform credential$'):
            platform.Client('modrinth', 'private-token\r\nInjected: value')

    def test_upload_failure_is_not_automatically_retried_or_logged(self):
        client = platform.Client('modrinth', 'test-token')
        client.opener = Mock()
        client.opener.open.side_effect = HTTPError('https://api.modrinth.com/v2/version', 503,
            'sensitive upstream text', {}, io.BytesIO(b'private-token'))
        with self.assertRaisesRegex(platform.ApiError, '^modrinth API returned HTTP 503$'):
            client.request('version', method='POST', data=b'file')
        self.assertEqual(1, client.opener.open.call_count)

    def test_redirect_does_not_forward_authorization(self):
        client = platform.Client('curseforge', 'test-token')
        client.opener = Mock()
        client.opener.open.side_effect = HTTPError('https://minecraft.curseforge.com/api/game/versions',
            302, 'Moved', {'Location': 'https://other.example/'}, io.BytesIO())
        with self.assertRaisesRegex(platform.ApiError, 'HTTP 302'):
            client.request('game/versions')
        self.assertEqual(1, client.opener.open.call_count)
        self.assertIsNone(platform.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example/'))

    def test_temporary_read_failure_is_bounded(self):
        client = platform.Client('modrinth', 'test-token')
        client.opener = Mock()
        client.opener.open.side_effect = HTTPError('https://api.modrinth.com/v2/project/example',
            503, 'Unavailable', {}, io.BytesIO())
        with patch.object(platform.time, 'sleep'), self.assertRaises(platform.ApiError):
            client.request('project/example')
        self.assertEqual(3, client.opener.open.call_count)


if __name__ == '__main__':
    unittest.main()
