<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head style="font-family:'Avenir Next','Helvetica Neue',Helvetica,Arial,sans-serif;font-size:100%;line-height:1.65;margin:0;padding:0">
    <meta http-equiv="Content-Type" content="text/html; charset=utf-8">
    <meta name="viewport" content="width=device-width">
</head>
<body style="-webkit-font-smoothing:antialiased;background:#efefef;font-family:'Avenir Next','Helvetica Neue',Helvetica,Arial,sans-serif;font-size:100%;height:100%;line-height:1.65;margin:0;padding:0;width:100%!important">
<table style="background:#efefef;font-family:'Avenir Next','Helvetica Neue',Helvetica,Arial,sans-serif;font-size:100%;height:100%;line-height:1.65;margin:0;padding:0;width:100%!important">
    <tbody>
    <tr>
        <td style="clear:both!important;display:block!important;margin:0 auto!important;max-width:680px!important;padding:0">
            <table style="border-collapse:collapse;width:100%!important;margin:0;padding:0">
                <tbody>
                <tr>
                    <td align="center" style="background:#1d6fa4;color:#fff;padding:80px 0">
                        <h1 style="font-size:32px;line-height:1.25;margin:0 auto!important;margin-bottom:20px;max-width:90%;padding:0">
                            Omnissa Access Application Request</h1>
                    </td>
                </tr>
                <tr>
                    <td style="background:#fff;padding:30px 35px">
                        <h2 style="font-size:28px;line-height:1.25;margin:0;margin-bottom:20px;padding:0">
                            Hi <#list request.userAttributes.firstName as firstName>${firstName}</#list> <#list request.userAttributes.lastName as lastName>${lastName}</#list>,
                        </h2>
                        <p style="font-size:16px;font-weight:400;line-height:1.65;margin:0;margin-bottom:20px;padding:0">
                            Your request for <strong>${request.resourceName}</strong> has been <b><i>approved</i></b>!
                        </p>
                        <#if request.responseMessage??>
                        <p style="font-size:16px;font-weight:400;line-height:1.65;margin:0;margin-bottom:20px;padding:0">
                            The following note was given as part of the approval:<br>
                            <i>${request.responseMessage}</i>
                        </p>
                        </#if>
                        <table style="border-collapse:collapse;width:100%!important;margin:0;padding:0">
                            <tbody>
                            <tr>
                                <td align="center" style="padding:0">
                                    <p style="font-size:16px;font-weight:400;line-height:1.65;margin:0;margin-bottom:20px;padding:0">
                                        <a href="${omnissaURL}"
                                           style="background:#1d6fa4;border:solid #1d6fa4;border-radius:4px;border-width:10px 20px 8px;color:#fff;display:inline-block;font-size:100%;font-weight:700;line-height:1.65;margin:0;padding:0;text-decoration:none">
                                            Launch <strong>${request.resourceName}</strong>
                                        </a>
                                    </p>
                                </td>
                            </tr>
                            </tbody>
                        </table>
                    </td>
                </tr>
                </tbody>
            </table>
            <table style="border-collapse:collapse;width:100%!important;margin:0;padding:0">
                <tbody>
                <tr>
                    <td align="center" style="background:0 0;padding:30px 35px">
                        <p style="color:#888;font-size:14px;font-weight:400;line-height:1.65;margin:0;text-align:center">
                            This message was sent by <strong>Omnissa Access Approval Tool</strong> — replies are not possible.
                        </p>
                    </td>
                </tr>
                </tbody>
            </table>
        </td>
    </tr>
    </tbody>
</table>
</body>
</html>
