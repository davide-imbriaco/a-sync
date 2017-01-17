$(function () {

    $('#wc_loading').hide();
    $('#wc_mainContent').show();

    $('#wc_exit').on('click', function () {
        $.get('/api/exit');
    });

});
